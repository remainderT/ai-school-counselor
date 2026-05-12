package org.buaa.rag.experiment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.buaa.rag.common.util.VectorMathUtils;
import org.buaa.rag.core.model.CragDecision;
import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.offline.chunk.ChunkingService;
import org.buaa.rag.core.offline.index.VectorEncoding;
import org.buaa.rag.core.online.chat.RagPromptService;
import org.buaa.rag.core.online.intent.IntentResolutionService;
import org.buaa.rag.core.online.retrieval.SmartRetrieverService;
import org.buaa.rag.core.online.retrieval.SubQueryRetrievalService;
import org.buaa.rag.core.online.retrieval.postprocessor.RetrievalPostProcessorService;
import org.buaa.rag.dao.entity.ChunkDO;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dao.mapper.ChunkMapper;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.KnowledgeMapper;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.tool.LlmChat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 高校辅导员 RAG 三组对照实验脚手架。
 *
 * <p>默认仅在显式传入 {@code -Drag.eval.enabled=true} 时运行，避免普通测试误触发远端依赖。
 *
 * <p>推荐运行顺序：
 * <ol>
 *   <li>{@link #exportCorpusSnapshot()}：导出线上知识库快照</li>
 *   <li>{@link #buildAutoEvaluationDataset()}：从真实 chunk 弱监督生成评测集</li>
 *   <li>人工检查并按需编辑输出目录中的 manual_eval_dataset.json</li>
 *   <li>{@link #runThreeWayComparison()}：运行 Baseline-A / Baseline-B / System 对照实验</li>
 * </ol>
 */
@Slf4j
@SpringBootTest(properties = {
    "spring.main.lazy-initialization=true",
    "stream.enabled=false",
    "spring.task.scheduling.enabled=false"
})
@EnabledIfSystemProperty(named = "rag.eval.enabled", matches = "true")
class RagExperimentManualTest {

    private static final String OWNER_USER_ID = "1";
    private static final int BASELINE_TOP_K = 5;
    private static final int AUTO_POSITIVE_CASES = 216;
    private static final int AUTO_NEGATIVE_CASES = 24;
    private static final int PER_KNOWLEDGE_CASES = 18;
    private static final int QUESTIONS_PER_CHUNK = 3;
    private static final int DATASET_CHECKPOINT_EVERY = 24;
    private static final long SAMPLE_SEED = 20260511L;
    private static final String NO_ANSWER_LABEL = "no_answer";
    private static final String OUTPUT_FOLDER = "evaluation-output/rag-experiment";
    private static final String CORPUS_FILE = "corpus_snapshot.json";
    private static final String AUTO_DATASET_FILE = "auto_eval_dataset.json";
    private static final String MANUAL_DATASET_FILE = "manual_eval_dataset.json";
    private static final String DESIGN_MD_FILE = "experiment_design.md";
    private static final String RESULT_JSON_FILE = "comparison_result.json";
    private static final String RESULT_CSV_FILE = "comparison_result.csv";
    private static final String REPORT_MD_FILE = "comparison_report.md";
    private static final String REGRESSION_DATASET_FILE = "system_regression_dataset.json";

    private static final String DATASET_BUILDER_SYSTEM_PROMPT = """
        你是高校知识库 RAG 评测集构建助手。
        任务：根据给定知识片段，生成最多 3 条可用于 RAG 评测的问答样本。

        约束：
        1. 只能依据提供片段，不得补充片段外信息。
        2. 问题必须是学生或老师自然会提的中文问题，不要照抄原文标题。
        3. 标准答案必须简洁、完整、可核验，不要扩写。
        4. 尽量覆盖不同问法或不同信息点，避免 3 条题只是同义改写。
        5. 如果片段噪声很大、表格残缺或不适合出题，返回 skip=true。
        6. 输出必须是 JSON，不要带 markdown 代码块。

        JSON Schema:
        {
          "skip": false,
          "reason": "",
          "cases": [
            {
              "query": "自然语言问题",
              "referenceAnswer": "标准答案",
              "questionType": "policy|procedure|material|time|eligibility|contact|other",
              "difficulty": "easy|medium|hard",
              "keywords": ["关键词1", "关键词2"],
              "evidenceSummary": "一句话说明该题为什么能由当前片段回答"
            }
          ]
        }
        """;

    private static final String JUDGE_SYSTEM_PROMPT = """
        你是 RAG 实验评审器，需要评价系统回答质量。
        请综合问题、标准答案、检索证据和系统回答，输出 JSON。

        评分标准：
        - correctness：1-5，是否与标准答案一致；5 表示基本完全正确。
        - faithfulness：1-5，是否忠于检索证据；5 表示无明显编造。
        - relevance：1-5，是否直接回答了用户问题。
        - fallbackAppropriate：布尔值。若题目本应无答案，系统是否进行了恰当兜底；若题目本应可回答，则回答 true 仅当系统没有错误拒答。
        - hallucination：布尔值，只要回答里有证据未支持或明显编造就为 true。
        - issueType：retrieval|generation|fallback|none
        - summary：一句中文结论，20 字以内

        输出必须是 JSON，不要带 markdown 代码块。
        """;

    @Autowired
    private KnowledgeMapper knowledgeMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private ChunkMapper chunkMapper;

    @Autowired
    private ChunkingService chunkingService;

    @Autowired
    private VectorEncoding vectorEncoding;

    @Autowired
    private LlmChat llmChat;

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private ObjectProvider<SmartRetrieverService> smartRetrieverServiceProvider;

    @Autowired
    private ObjectProvider<RagPromptService> ragPromptServiceProvider;

    @Autowired
    private ObjectProvider<IntentResolutionService> intentResolutionServiceProvider;

    @Autowired
    private ObjectProvider<SubQueryRetrievalService> subQueryRetrievalServiceProvider;

    @Autowired
    private ObjectProvider<RetrievalPostProcessorService> retrievalPostProcessorServiceProvider;

    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private volatile BaselineBIndex cachedBaselineBIndex;

    @Test
    void exportCorpusSnapshot() throws Exception {
        CorpusSnapshot snapshot = buildCorpusSnapshot();
        writeJson(outputDir().resolve(CORPUS_FILE), snapshot);
        writeJson(outputDir().resolve("manual_eval_dataset.template.json"), buildManualTemplate(snapshot));
        writeString(outputDir().resolve(DESIGN_MD_FILE), buildExperimentDesignMarkdown(snapshot, null));
        log.info("知识库快照已导出: docs={}, chunks={}, dir={}",
            snapshot.documents().size(), snapshot.chunks().size(), outputDir().toAbsolutePath());
    }

    @Test
    void buildAutoEvaluationDataset() throws Exception {
        CorpusSnapshot snapshot = buildCorpusSnapshot();
        List<EvalChunk> sampledChunks = samplePositiveChunks(snapshot);
        List<EvalCase> positives = new ArrayList<>();
        Set<String> seenQueries = new LinkedHashSet<>();

        for (EvalChunk chunk : sampledChunks) {
            List<EvalCase> generatedCases = generatePositiveEvalCases(chunk, QUESTIONS_PER_CHUNK);
            for (EvalCase evalCase : generatedCases) {
                String normalizedQuery = cleanInline(evalCase.query()).toLowerCase(Locale.ROOT);
                if (!StringUtils.hasText(normalizedQuery) || !seenQueries.add(normalizedQuery)) {
                    continue;
                }
                positives.add(evalCase);
                if (positives.size() % DATASET_CHECKPOINT_EVERY == 0) {
                    writeDatasetCheckpoint(snapshot, positives);
                }
                if (positives.size() >= AUTO_POSITIVE_CASES) {
                    break;
                }
            }
            if (positives.size() >= AUTO_POSITIVE_CASES) {
                break;
            }
        }

        List<EvalCase> negatives = buildNegativeCases(snapshot);
        List<EvalCase> cases = new ArrayList<>(positives.size() + negatives.size());
        cases.addAll(positives);
        cases.addAll(negatives);

        EvalDataset dataset = new EvalDataset(
            "ai-school-counselor-rag-eval",
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            """
                自动评测集来自线上 MySQL 真实知识库。
                正样本由单个高质量 chunk 弱监督生成，负样本为明显超出当前高校知识库边界的问题。
                建议先人工抽查和微调 manual_eval_dataset.json，再用于论文正式跑分。
                """.trim(),
            cases
        );

        writeJson(outputDir().resolve(AUTO_DATASET_FILE), dataset);
        writeJson(outputDir().resolve(MANUAL_DATASET_FILE), dataset);
        writeString(outputDir().resolve(DESIGN_MD_FILE), buildExperimentDesignMarkdown(snapshot, dataset));
        log.info("自动评测集已生成: positive={}, negative={}, total={}",
            positives.size(), negatives.size(), cases.size());
    }

    @Test
    void runThreeWayComparison() throws Exception {
        runComparison(loadDatasetForRun(), Arrays.asList(ExperimentVariant.values()), "");
    }

    @Test
    void buildSystemRegressionDataset() throws Exception {
        EvalDataset dataset = loadDatasetForRun();
        List<String> keywords = List.of(
            "资源盾牌",
            "研究生招生",
            "图书馆",
            "临时困难补助",
            "中英文成绩",
            "证书翻译",
            "国际交流",
            "公派研究生",
            "监理",
            "设计业务分包",
            "开户行代码",
            "发票",
            "统一社会信用代码"
        );
        Set<String> seen = new LinkedHashSet<>();
        List<EvalCase> selected = dataset.cases().stream()
            .filter(item -> keywords.stream().anyMatch(keyword -> item.query().contains(keyword)))
            .filter(item -> seen.add(item.query()))
            .limit(36)
            .toList();

        EvalDataset regressionDataset = new EvalDataset(
            dataset.datasetName() + "-system-regression",
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "用于快速验证 System 优化方向的回归子集，覆盖错误拒答、证据不足编造和制度类精确问答。",
            selected
        );
        writeJson(outputDir().resolve(REGRESSION_DATASET_FILE), regressionDataset);
        log.info("System 回归子集已生成: total={}", selected.size());
    }

    @Test
    void runSystemRegressionComparison() throws Exception {
        Path regressionPath = outputDir().resolve(REGRESSION_DATASET_FILE);
        EvalDataset dataset;
        if (Files.exists(regressionPath)) {
            dataset = objectMapper.readValue(Files.readString(regressionPath, StandardCharsets.UTF_8), EvalDataset.class);
        } else {
            buildSystemRegressionDataset();
            dataset = objectMapper.readValue(Files.readString(regressionPath, StandardCharsets.UTF_8), EvalDataset.class);
        }
        runComparison(dataset, List.of(ExperimentVariant.SYSTEM), "system_regression");
    }

    private CorpusSnapshot buildCorpusSnapshot() {
        List<KnowledgeDO> knowledgeList = knowledgeMapper.selectList(
            Wrappers.lambdaQuery(KnowledgeDO.class)
                .eq(KnowledgeDO::getDelFlag, 0)
                .orderByAsc(KnowledgeDO::getId)
        );
        List<DocumentDO> documents = documentMapper.selectList(
            Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getDelFlag, 0)
                .eq(DocumentDO::getUserId, Long.parseLong(OWNER_USER_ID))
                .orderByAsc(DocumentDO::getId)
        );
        List<ChunkDO> chunks = chunkMapper.selectList(
            Wrappers.lambdaQuery(ChunkDO.class)
                .eq(ChunkDO::getDelFlag, 0)
                .eq(ChunkDO::getEnabled, 1)
                .orderByAsc(ChunkDO::getDocumentId)
                .orderByAsc(ChunkDO::getFragmentIndex)
        );

        Map<Long, KnowledgeDO> knowledgeMap = knowledgeList.stream()
            .collect(Collectors.toMap(KnowledgeDO::getId, item -> item));
        Map<Long, List<ChunkDO>> chunkMap = chunks.stream()
            .collect(Collectors.groupingBy(ChunkDO::getDocumentId, LinkedHashMap::new, Collectors.toList()));

        List<DocumentSnapshot> documentSnapshots = new ArrayList<>();
        List<EvalChunk> evalChunks = new ArrayList<>();
        List<KnowledgeSnapshot> knowledgeSnapshots = new ArrayList<>();
        Map<Long, Integer> knowledgeDocCount = new HashMap<>();
        Map<Long, Integer> knowledgeChunkCount = new HashMap<>();

        for (DocumentDO document : documents) {
            KnowledgeDO knowledge = knowledgeMap.get(document.getKnowledgeId());
            List<ChunkDO> docChunks = chunkMap.getOrDefault(document.getId(), List.of());
            String fullText = docChunks.stream()
                .map(ChunkDO::getTextData)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n"));

            documentSnapshots.add(new DocumentSnapshot(
                document.getId(),
                document.getKnowledgeId(),
                knowledge == null ? "unknown_kb" : knowledge.getName(),
                document.getOriginalFileName(),
                document.getChunkMode(),
                docChunks.size(),
                compactText(fullText, 6000)
            ));

            knowledgeDocCount.merge(document.getKnowledgeId(), 1, Integer::sum);
            knowledgeChunkCount.merge(document.getKnowledgeId(), docChunks.size(), Integer::sum);

            for (ChunkDO chunk : docChunks) {
                evalChunks.add(new EvalChunk(
                    document.getId(),
                    document.getKnowledgeId(),
                    knowledge == null ? "unknown_kb" : knowledge.getName(),
                    document.getOriginalFileName(),
                    chunk.getFragmentIndex(),
                    sanitizeSnippet(chunk.getTextData()),
                    document.getMd5Hash()
                ));
            }
        }

        for (KnowledgeDO knowledge : knowledgeList) {
            knowledgeSnapshots.add(new KnowledgeSnapshot(
                knowledge.getId(),
                knowledge.getName(),
                knowledge.getDescription(),
                knowledgeDocCount.getOrDefault(knowledge.getId(), 0),
                knowledgeChunkCount.getOrDefault(knowledge.getId(), 0)
            ));
        }

        return new CorpusSnapshot(
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            knowledgeSnapshots,
            documentSnapshots,
            evalChunks
        );
    }

    private EvalDataset buildManualTemplate(CorpusSnapshot snapshot) {
        List<EvalCase> templateCases = samplePositiveChunks(snapshot).stream()
            .limit(12)
            .map(chunk -> new EvalCase(
                "请人工填写问题",
                "请人工填写标准答案",
                "policy",
                "medium",
                chunk.knowledgeName(),
                List.of(new ChunkRef(chunk.documentId(), chunk.fragmentIndex())),
                false,
                chunk.fileName(),
                List.of("示例"),
                "manual_template"
            ))
            .toList();
        return new EvalDataset(
            "manual-template",
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "人工标注模板。建议补成 50~100 条正式评测集。",
            templateCases
        );
    }

    private List<EvalChunk> samplePositiveChunks(CorpusSnapshot snapshot) {
        Map<String, List<EvalChunk>> grouped = snapshot.chunks().stream()
            .filter(this::isHighQualityChunk)
            .collect(Collectors.groupingBy(EvalChunk::knowledgeName, LinkedHashMap::new, Collectors.toList()));

        Random random = new Random(SAMPLE_SEED);
        List<EvalChunk> sampled = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, List<EvalChunk>> entry : grouped.entrySet()) {
            List<EvalChunk> candidates = new ArrayList<>(entry.getValue());
            Collections.shuffle(candidates, random);
            for (EvalChunk candidate : candidates.stream().limit(PER_KNOWLEDGE_CASES).toList()) {
                String key = candidate.documentId() + "#" + candidate.fragmentIndex();
                if (seen.add(key)) {
                    sampled.add(candidate);
                }
            }
        }

        List<EvalChunk> remaining = grouped.values().stream()
            .flatMap(List::stream)
            .filter(chunk -> seen.add(chunk.documentId() + "#" + chunk.fragmentIndex()))
            .collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(remaining, random);
        Collections.shuffle(sampled, random);
        sampled.addAll(remaining);
        return sampled;
    }

    private boolean isHighQualityChunk(EvalChunk chunk) {
        if (chunk == null || !StringUtils.hasText(chunk.text())) {
            return false;
        }
        String text = chunk.text().trim();
        if (text.length() < 120 || text.length() > 1600) {
            return false;
        }
        long newlineCount = text.chars().filter(ch -> ch == '\n').count();
        if (newlineCount > 40) {
            return false;
        }
        long meaningfulChars = text.chars()
            .filter(ch -> Character.isLetterOrDigit(ch) || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN)
            .count();
        return meaningfulChars >= text.length() * 0.45;
    }

    private List<EvalCase> generatePositiveEvalCases(EvalChunk chunk, int maxCases) {
        String payload = """
            知识库：%s
            文件名：%s
            片段编号：%s
            最多出题数：%s
            片段内容：
            %s
            """.formatted(chunk.knowledgeName(), chunk.fileName(), chunk.fragmentIndex(), maxCases, chunk.text());

        String response = llmChat.generateCompletion(
            DATASET_BUILDER_SYSTEM_PROMPT,
            payload,
            900,
            0.2,
            0.4
        );
        JsonNode node = safeReadJson(response);
        if (node == null || node.path("skip").asBoolean(false)) {
            return List.of();
        }

        JsonNode casesNode = node.path("cases");
        if (!casesNode.isArray() || casesNode.isEmpty()) {
            if (StringUtils.hasText(cleanInline(node.path("query").asText("")))) {
                EvalCase single = toEvalCase(node, chunk);
                return single == null ? List.of() : List.of(single);
            }
            return List.of();
        }

        List<EvalCase> result = new ArrayList<>();
        for (JsonNode caseNode : casesNode) {
            EvalCase evalCase = toEvalCase(caseNode, chunk);
            if (evalCase != null) {
                result.add(evalCase);
            }
            if (result.size() >= maxCases) {
                break;
            }
        }
        return result;
    }

    private EvalCase toEvalCase(JsonNode node, EvalChunk chunk) {
        String query = cleanInline(node.path("query").asText(""));
        String referenceAnswer = cleanInline(node.path("referenceAnswer").asText(""));
        if (!StringUtils.hasText(query) || !StringUtils.hasText(referenceAnswer)) {
            return null;
        }

        List<String> keywords = new ArrayList<>();
        JsonNode keywordsNode = node.path("keywords");
        if (keywordsNode.isArray()) {
            for (JsonNode item : keywordsNode) {
                String keyword = cleanInline(item.asText(""));
                if (StringUtils.hasText(keyword)) {
                    keywords.add(keyword);
                }
            }
        }

        return new EvalCase(
            query,
            referenceAnswer,
            cleanInline(node.path("questionType").asText("other")),
            cleanInline(node.path("difficulty").asText("medium")),
            chunk.knowledgeName(),
            List.of(new ChunkRef(chunk.documentId(), chunk.fragmentIndex())),
            false,
            chunk.fileName(),
            keywords,
            "llm_generated_from_chunk"
        );
    }

    private void writeDatasetCheckpoint(CorpusSnapshot snapshot, List<EvalCase> positives) throws IOException {
        List<EvalCase> negatives = buildNegativeCases(snapshot);
        List<EvalCase> cases = new ArrayList<>(positives.size() + negatives.size());
        cases.addAll(positives);
        cases.addAll(negatives);
        EvalDataset checkpoint = new EvalDataset(
            "ai-school-counselor-rag-eval",
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "自动评测集检查点，可继续人工补充或直接用于中间复核。",
            cases
        );
        writeJson(outputDir().resolve(AUTO_DATASET_FILE), checkpoint);
        writeJson(outputDir().resolve(MANUAL_DATASET_FILE), checkpoint);
        writeString(outputDir().resolve(DESIGN_MD_FILE), buildExperimentDesignMarkdown(snapshot, checkpoint));
        log.info("评测集检查点已写入: positives={}, total={}", positives.size(), cases.size());
    }

    private List<EvalCase> buildNegativeCases(CorpusSnapshot snapshot) {
        List<String> questions = List.of(
            "iPhone 17 的上市时间是什么时候？",
            "比特币未来三个月会涨到多少？",
            "上海迪士尼周末门票价格是多少？",
            "如何给特斯拉 Model Y 更换轮胎？",
            "巴黎奥运会男子 100 米冠军是谁？",
            "Windows 12 什么时候正式发布？",
            "家用空气炸锅哪个品牌最好？",
            "怎么给猫咪治疗耳螨？",
            "英超下轮曼城和阿森纳谁更可能赢？",
            "高血压患者每天应该吃多少毫克降压药？",
            "2026 年国考行测题型有哪些变化？",
            "成都到九寨沟自驾最佳路线是什么？",
            "哪款游戏本在 8000 元价位最值得买？",
            "如何判断基金现在是不是抄底时机？",
            "美股纳斯达克今晚会高开还是低开？",
            "怎样在家里自己给汽车补漆？",
            "OpenAI 最新旗舰模型的价格是多少？",
            "2026 年考研数学一难度会不会继续上涨？",
            "肺结节 6 毫米需要马上手术吗？",
            "日本旅游签证最近多久能办下来？",
            "小红书上最适合新手起号的赛道是什么？",
            "荣耀最新折叠屏手机参数怎么样？",
            "2026 年世界杯扩军后的赛制怎么变了？",
            "怎么配置 Kubernetes 才能让生产集群更省钱？",
            "现在买黄金还是买美债更合适？",
            "北京学区房今年下半年还会涨吗？",
            "剖腹产后多久可以恢复跑步训练？",
            "Switch 2 的首发游戏阵容有哪些？",
            "企业所得税最新减免政策适用于所有行业吗？",
            "如何训练一个宠物鹦鹉学会说话？",
            "哪家保险公司的重疾险理赔口碑最好？",
            "怎么自己在家做意式浓缩咖啡拉花？"
        );
        String fallbackAnswer = "当前知识库暂无相关信息，建议查询对应官方渠道。";
        return questions.stream()
            .limit(AUTO_NEGATIVE_CASES)
            .map(question -> new EvalCase(
                question,
                fallbackAnswer,
                NO_ANSWER_LABEL,
                "easy",
                snapshot.knowledge().stream().map(KnowledgeSnapshot::name).collect(Collectors.joining(",")),
                List.of(),
                true,
                "",
                List.of("out_of_scope"),
                "manual_negative"
            ))
            .toList();
    }

    private EvalDataset loadDatasetForRun() throws IOException {
        Path manual = outputDir().resolve(MANUAL_DATASET_FILE);
        Path auto = outputDir().resolve(AUTO_DATASET_FILE);
        Path target = Files.exists(manual) ? manual : auto;
        if (!Files.exists(target)) {
            throw new IllegalStateException("未找到评测集，请先运行 buildAutoEvaluationDataset()");
        }
        return objectMapper.readValue(Files.readString(target, StandardCharsets.UTF_8), EvalDataset.class);
    }

    private void runComparison(EvalDataset dataset,
                               List<ExperimentVariant> variants,
                               String outputTag) throws Exception {
        BaselineBIndex baselineBIndex = variants.contains(ExperimentVariant.BASELINE_B)
            ? getOrBuildBaselineBIndex()
            : null;
        List<CaseRunRecord> records = new ArrayList<>();

        for (EvalCase evalCase : dataset.cases()) {
            for (ExperimentVariant variant : variants) {
                records.add(runSingleCase(evalCase, variant, baselineBIndex));
            }
        }

        List<VariantSummary> summaries = variants.stream()
            .map(variant -> summarizeVariant(variant, records, dataset))
            .toList();

        ComparisonResult result = new ComparisonResult(
            dataset.datasetName(),
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            outputDir().toAbsolutePath().toString(),
            summaries,
            records
        );

        String suffix = StringUtils.hasText(outputTag) ? "_" + outputTag : "";
        writeJson(outputDir().resolve(fileWithSuffix(RESULT_JSON_FILE, suffix)), result);
        writeString(outputDir().resolve(fileWithSuffix(RESULT_CSV_FILE, suffix)), buildCsv(records));
        writeString(outputDir().resolve(fileWithSuffix(REPORT_MD_FILE, suffix)), buildMarkdownReport(dataset, summaries, records));
        writeString(outputDir().resolve(DESIGN_MD_FILE), buildExperimentDesignMarkdown(buildCorpusSnapshot(), dataset));
        log.info("对照实验已完成: variants={}, records={}, suffix={}",
            variants.stream().map(Enum::name).toList(), records.size(), suffix);
    }

    private CaseRunRecord runSingleCase(EvalCase evalCase,
                                        ExperimentVariant variant,
                                        BaselineBIndex baselineBIndex) {
        VariantRunResult runResult = switch (variant) {
            case BASELINE_A -> runBaselineA(evalCase);
            case BASELINE_B -> runBaselineB(evalCase, baselineBIndex);
            case SYSTEM -> runSystem(evalCase);
        };
        JudgeResult judge = judge(evalCase, runResult);
        RetrievalMetrics retrieval = computeRetrievalMetrics(evalCase, runResult.retrievalMatches());
        return new CaseRunRecord(
            variant.name(),
            evalCase.query(),
            evalCase.expectedNoAnswer(),
            evalCase.questionType(),
            evalCase.difficulty(),
            evalCase.knowledgeScope(),
            runResult.answer(),
            runResult.cragAction(),
            runResult.retrievalTopK(),
            runResult.retrievalMatches().stream().map(this::toRetrievedChunk).toList(),
            retrieval,
            judge
        );
    }

    private VariantRunResult runBaselineA(EvalCase evalCase) {
        List<RetrievalMatch> matches = getSmartRetrieverService().retrieveTextOnly(
            evalCase.query(),
            BASELINE_TOP_K,
            OWNER_USER_ID
        );
        String answer = generateAnswer(evalCase.query(), matches, null);
        return new VariantRunResult(answer, matches, BASELINE_TOP_K, "ANSWER");
    }

    private VariantRunResult runBaselineB(EvalCase evalCase, BaselineBIndex baselineBIndex) {
        List<RetrievalMatch> matches = baselineBIndex.search(evalCase.query(), BASELINE_TOP_K);
        String answer = generateAnswer(evalCase.query(), matches, null);
        return new VariantRunResult(answer, matches, BASELINE_TOP_K, "ANSWER");
    }

    private VariantRunResult runSystem(EvalCase evalCase) {
        List<IntentDecision> candidates = getIntentResolutionService().resolveForQuery(OWNER_USER_ID, evalCase.query());
        IntentDecision primary = candidates.isEmpty()
            ? getSubQueryRetrievalService().defaultHybridIntent()
            : candidates.get(0);

        int topK = primary.getTopK() != null && primary.getTopK() > 0
            ? Math.min(primary.getTopK(), ragProperties.getRetrieval().getMaxTopK())
            : getSubQueryRetrievalService().determineTopK(evalCase.query());

        List<RetrievalMatch> retrievalMatches = getSubQueryRetrievalService().retrieveByStrategy(
            OWNER_USER_ID,
            evalCase.query(),
            topK,
            primary,
            candidates
        );

        CragDecision decision = getRetrievalPostProcessorService().evaluate(evalCase.query(), retrievalMatches);
        if (decision.getAction() == CragDecision.Action.CLARIFY
            || decision.getAction() == CragDecision.Action.NO_ANSWER) {
            return new VariantRunResult(
                decision.getMessage(),
                getRagPromptService().limitSourcesForAnswer(retrievalMatches),
                topK,
                decision.getAction().name()
            );
        }

        if (decision.getAction() == CragDecision.Action.REFINE) {
            List<RetrievalMatch> fallback = getSubQueryRetrievalService().fallbackRetrieval(
                OWNER_USER_ID,
                evalCase.query(),
                topK
            );
            if (!fallback.isEmpty()) {
                retrievalMatches = fallback;
            }
        }

        List<RetrievalMatch> displayedSources = getRagPromptService().limitSourcesForAnswer(retrievalMatches);
        RagPromptService.IntentPromptDescriptor descriptor = new RagPromptService.IntentPromptDescriptor(
            primary.getLevel2(),
            primary.getPromptTemplate(),
            primary.getPromptSnippet()
        );
        String answer = generateAnswer(evalCase.query(), displayedSources, descriptor);
        return new VariantRunResult(answer, displayedSources, topK, decision.getAction().name());
    }

    private String generateAnswer(String query,
                                  List<RetrievalMatch> sources,
                                  RagPromptService.IntentPromptDescriptor descriptor) {
        return getRagPromptService().generateSingleIntentStructuredAnswer(
            query,
            List.of(),
            sources == null ? List.of() : sources,
            descriptor,
            ignored -> {
            },
            false,
            null
        );
    }

    private JudgeResult judge(EvalCase evalCase, VariantRunResult runResult) {
        String evidenceText = runResult.retrievalMatches().stream()
            .limit(3)
            .map(match -> "- [%s#%s] %s".formatted(
                safe(match.getSourceFileName()),
                safe(match.getChunkId()),
                compactText(match.getTextContent(), 220)))
            .collect(Collectors.joining("\n"));

        String userPrompt = """
            问题：%s
            标准答案：%s
            是否应无答案：%s
            检索证据：
            %s

            系统回答：
            %s
            """.formatted(
            evalCase.query(),
            evalCase.referenceAnswer(),
            evalCase.expectedNoAnswer(),
            StringUtils.hasText(evidenceText) ? evidenceText : "（无检索证据）",
            safe(runResult.answer())
        );

        String raw = llmChat.generateCompletion(
            JUDGE_SYSTEM_PROMPT,
            userPrompt,
            512,
            0.1,
            0.3
        );
        JsonNode node = safeReadJson(raw);
        if (node == null) {
            return fallbackJudge(evalCase, runResult);
        }

        int correctness = clampScore(node.path("correctness").asInt(3));
        int faithfulness = clampScore(node.path("faithfulness").asInt(3));
        int relevance = clampScore(node.path("relevance").asInt(3));
        boolean fallbackAppropriate = node.path("fallbackAppropriate").asBoolean(!evalCase.expectedNoAnswer());
        boolean hallucination = node.path("hallucination").asBoolean(false);
        String issueType = cleanInline(node.path("issueType").asText("none"));
        String summary = cleanInline(node.path("summary").asText(""));

        return new JudgeResult(correctness, faithfulness, relevance, fallbackAppropriate, hallucination, issueType, summary);
    }

    private JudgeResult fallbackJudge(EvalCase evalCase, VariantRunResult runResult) {
        String answer = safe(runResult.answer()).toLowerCase(Locale.ROOT);
        boolean abstain = answer.contains("暂无")
            || answer.contains("未找到")
            || answer.contains("没有找到")
            || answer.contains("建议查询")
            || answer.contains("抱歉");

        if (evalCase.expectedNoAnswer()) {
            return new JudgeResult(
                abstain ? 5 : 2,
                abstain ? 5 : 2,
                abstain ? 5 : 3,
                abstain,
                !abstain,
                abstain ? "none" : "fallback",
                abstain ? "兜底基本合理" : "未正确兜底"
            );
        }

        boolean hasEvidence = runResult.retrievalMatches() != null && !runResult.retrievalMatches().isEmpty();
        return new JudgeResult(
            hasEvidence ? 3 : 2,
            hasEvidence ? 3 : 2,
            3,
            hasEvidence,
            false,
            hasEvidence ? "none" : "retrieval",
            hasEvidence ? "需人工复核" : "检索偏弱"
        );
    }

    private RetrievalMetrics computeRetrievalMetrics(EvalCase evalCase, List<RetrievalMatch> matches) {
        if (evalCase.relevantChunks() == null || evalCase.relevantChunks().isEmpty()) {
            return new RetrievalMetrics(0.0, 0.0, 0.0, 0.0);
        }
        Set<String> expected = evalCase.relevantChunks().stream()
            .map(ref -> ref.documentId() + "#" + ref.fragmentIndex())
            .collect(Collectors.toCollection(LinkedHashSet::new));

        double hit = 0.0;
        double mrr = 0.0;
        int correctCount = 0;
        int rank = 0;

        for (RetrievalMatch match : matches) {
            rank++;
            String actual = match.getDocumentId() + "#" + match.getChunkId();
            if (expected.contains(actual)) {
                correctCount++;
                hit = 1.0;
                if (mrr == 0.0) {
                    mrr = 1.0 / rank;
                }
            }
        }

        double recall = expected.isEmpty() ? 0.0 : correctCount * 1.0 / expected.size();
        double precision = matches.isEmpty() ? 0.0 : correctCount * 1.0 / matches.size();
        return new RetrievalMetrics(hit, mrr, recall, precision);
    }

    private VariantSummary summarizeVariant(ExperimentVariant variant,
                                            List<CaseRunRecord> records,
                                            EvalDataset dataset) {
        List<CaseRunRecord> scoped = records.stream()
            .filter(record -> variant.name().equals(record.variant()))
            .toList();

        List<CaseRunRecord> positive = scoped.stream()
            .filter(record -> !record.expectedNoAnswer())
            .toList();
        List<CaseRunRecord> negative = scoped.stream()
            .filter(CaseRunRecord::expectedNoAnswer)
            .toList();

        return new VariantSummary(
            variant.name(),
            variant.label,
            avg(positive.stream().map(record -> record.retrieval().hitRate()).toList()),
            avg(positive.stream().map(record -> record.retrieval().mrr()).toList()),
            avg(positive.stream().map(record -> record.retrieval().recall()).toList()),
            avg(positive.stream().map(record -> record.retrieval().precision()).toList()),
            avg(scoped.stream().map(record -> (double) record.judge().correctness()).toList()),
            avg(scoped.stream().map(record -> (double) record.judge().faithfulness()).toList()),
            avg(scoped.stream().map(record -> (double) record.judge().relevance()).toList()),
            ratio(scoped.stream().filter(record -> record.judge().correctness() >= 4).count(), scoped.size()),
            ratio(scoped.stream().filter(record -> record.judge().hallucination()).count(), scoped.size()),
            ratio(negative.stream().filter(record -> record.judge().fallbackAppropriate()).count(), negative.size()),
            dataset.cases().size()
        );
    }

    private BaselineBIndex getOrBuildBaselineBIndex() {
        BaselineBIndex current = cachedBaselineBIndex;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cachedBaselineBIndex != null) {
                return cachedBaselineBIndex;
            }
            cachedBaselineBIndex = buildBaselineBIndex();
            return cachedBaselineBIndex;
        }
    }

    private BaselineBIndex buildBaselineBIndex() {
        List<DocumentDO> documents = documentMapper.selectList(
            Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getDelFlag, 0)
                .eq(DocumentDO::getUserId, Long.parseLong(OWNER_USER_ID))
                .orderByAsc(DocumentDO::getId)
        );
        List<ChunkDO> chunks = chunkMapper.selectList(
            Wrappers.lambdaQuery(ChunkDO.class)
                .eq(ChunkDO::getDelFlag, 0)
                .eq(ChunkDO::getEnabled, 1)
                .orderByAsc(ChunkDO::getDocumentId)
                .orderByAsc(ChunkDO::getFragmentIndex)
        );
        List<KnowledgeDO> knowledgeList = knowledgeMapper.selectList(
            Wrappers.lambdaQuery(KnowledgeDO.class).eq(KnowledgeDO::getDelFlag, 0)
        );

        Map<Long, KnowledgeDO> knowledgeMap = knowledgeList.stream()
            .collect(Collectors.toMap(KnowledgeDO::getId, item -> item));
        Map<Long, List<ChunkDO>> chunkMap = chunks.stream()
            .collect(Collectors.groupingBy(ChunkDO::getDocumentId, LinkedHashMap::new, Collectors.toList()));

        List<BaselineBChunk> rebuilt = new ArrayList<>();
        for (DocumentDO document : documents) {
            List<ChunkDO> docChunks = chunkMap.getOrDefault(document.getId(), List.of());
            String fullText = docChunks.stream()
                .map(ChunkDO::getTextData)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n"));
            if (!StringUtils.hasText(fullText)) {
                continue;
            }
            List<String> fixedChunks = chunkingService.chunk(fullText, 512, "fixed_size");
            KnowledgeDO knowledge = knowledgeMap.get(document.getKnowledgeId());
            for (int idx = 0; idx < fixedChunks.size(); idx++) {
                rebuilt.add(new BaselineBChunk(
                    document.getId(),
                    document.getMd5Hash(),
                    idx,
                    fixedChunks.get(idx),
                    document.getOriginalFileName(),
                    knowledge == null ? "unknown_kb" : knowledge.getName()
                ));
            }
        }

        List<String> texts = rebuilt.stream().map(BaselineBChunk::text).toList();
        List<float[]> embeddings = batchEncode(texts, 10);
        List<BaselineBChunkVector> vectors = new ArrayList<>();
        for (int i = 0; i < Math.min(rebuilt.size(), embeddings.size()); i++) {
            vectors.add(new BaselineBChunkVector(rebuilt.get(i), embeddings.get(i)));
        }
        log.info("Baseline-B 固定分块索引构建完成: docs={}, chunks={}", documents.size(), vectors.size());
        return new BaselineBIndex(vectors);
    }

    private List<float[]> batchEncode(List<String> texts, int batchSize) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(texts.size(), start + batchSize);
            result.addAll(vectorEncoding.encode(texts.subList(start, end)));
        }
        return result;
    }

    private RetrievedChunk toRetrievedChunk(RetrievalMatch match) {
        return new RetrievedChunk(
            match.getDocumentId(),
            match.getChunkId(),
            safe(match.getSourceFileName()),
            match.getRelevanceScore() == null ? 0.0 : match.getRelevanceScore(),
            compactText(match.getTextContent(), 180)
        );
    }

    private String buildCsv(List<CaseRunRecord> records) {
        StringBuilder builder = new StringBuilder();
        builder.append("variant,question,expected_no_answer,question_type,difficulty,knowledge_scope,crag_action,hit,mrr,recall,precision,correctness,faithfulness,relevance,fallback_appropriate,hallucination,issue_type,summary\n");
        for (CaseRunRecord record : records) {
            builder.append(csv(record.variant())).append(',')
                .append(csv(record.question())).append(',')
                .append(record.expectedNoAnswer()).append(',')
                .append(csv(record.questionType())).append(',')
                .append(csv(record.difficulty())).append(',')
                .append(csv(record.knowledgeScope())).append(',')
                .append(csv(record.cragAction())).append(',')
                .append(record.retrieval().hitRate()).append(',')
                .append(record.retrieval().mrr()).append(',')
                .append(record.retrieval().recall()).append(',')
                .append(record.retrieval().precision()).append(',')
                .append(record.judge().correctness()).append(',')
                .append(record.judge().faithfulness()).append(',')
                .append(record.judge().relevance()).append(',')
                .append(record.judge().fallbackAppropriate()).append(',')
                .append(record.judge().hallucination()).append(',')
                .append(csv(record.judge().issueType())).append(',')
                .append(csv(record.judge().summary()))
                .append('\n');
        }
        return builder.toString();
    }

    private String buildMarkdownReport(EvalDataset dataset,
                                       List<VariantSummary> summaries,
                                       List<CaseRunRecord> records) {
        StringBuilder builder = new StringBuilder();
        builder.append("# RAG 三组对照实验报告\n\n");
        builder.append("## 实验设计\n");
        builder.append("- Baseline-A：纯 BM25 关键词检索 + 直接生成。\n");
        builder.append("- Baseline-B：朴素 RAG，使用固定 512 字符重分块 + 单路向量检索。\n");
        builder.append("- System：复用当前系统的结构感知分块、多通道混合检索、重排和 CRAG 评估。\n\n");

        builder.append("## 数据集\n");
        builder.append("- 数据集名称：").append(dataset.datasetName()).append('\n');
        builder.append("- 样本总数：").append(dataset.cases().size()).append('\n');
        builder.append("- 可回答样本：").append(dataset.cases().stream().filter(item -> !item.expectedNoAnswer()).count()).append('\n');
        builder.append("- 无答案样本：").append(dataset.cases().stream().filter(EvalCase::expectedNoAnswer).count()).append("\n\n");

        builder.append("## 汇总指标\n\n");
        builder.append("| Variant | HitRate | MRR | Recall | Precision | Correctness | Faithfulness | Relevance | Accuracy | Hallucination | Fallback |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (VariantSummary summary : summaries) {
            builder.append("| ").append(summary.label()).append(" | ")
                .append(percent(summary.hitRate())).append(" | ")
                .append(format3(summary.mrr())).append(" | ")
                .append(percent(summary.recall())).append(" | ")
                .append(percent(summary.precision())).append(" | ")
                .append(format2(summary.correctness())).append(" | ")
                .append(format2(summary.faithfulness())).append(" | ")
                .append(format2(summary.relevance())).append(" | ")
                .append(percent(summary.answerAccuracy())).append(" | ")
                .append(percent(summary.hallucinationRate())).append(" | ")
                .append(percent(summary.fallbackSuccessRate())).append(" |\n");
        }

        builder.append("\n## 典型问题归因\n");
        for (ExperimentVariant variant : ExperimentVariant.values()) {
            List<CaseRunRecord> topIssues = records.stream()
                .filter(record -> variant.name().equals(record.variant()))
                .sorted(Comparator.comparingInt((CaseRunRecord record) -> record.judge().correctness())
                    .thenComparing(record -> record.judge().faithfulness()))
                .limit(3)
                .toList();
            builder.append("\n### ").append(variant.label).append('\n');
            for (CaseRunRecord issue : topIssues) {
                builder.append("- 问题：").append(issue.question()).append('\n');
                builder.append("  结论：").append(issue.judge().summary()).append("；问题类型：").append(issue.judge().issueType()).append('\n');
            }
        }

        builder.append("\n## 论文撰写建议\n");
        builder.append("- 正式论文建议优先使用 manual_eval_dataset.json，经人工复核后再跑最终分数。\n");
        builder.append("- 若 Baseline-B 在 HitRate 提升但 Faithfulness 下降，可据此论证“仅增大召回不足以提升端到端效果”。\n");
        builder.append("- 若 System 的 Hallucination 更低、Fallback 更稳，可突出 CRAG 和混合检索的贡献。\n");
        return builder.toString();
    }

    private String buildExperimentDesignMarkdown(CorpusSnapshot snapshot, EvalDataset dataset) {
        int knowledgeCount = snapshot == null || snapshot.knowledge() == null ? 0 : snapshot.knowledge().size();
        int documentCount = snapshot == null || snapshot.documents() == null ? 0 : snapshot.documents().size();
        int chunkCount = snapshot == null || snapshot.chunks() == null ? 0 : snapshot.chunks().size();
        int totalCases = dataset == null || dataset.cases() == null
            ? AUTO_POSITIVE_CASES + AUTO_NEGATIVE_CASES
            : dataset.cases().size();
        long answerableCases = dataset == null || dataset.cases() == null
            ? AUTO_POSITIVE_CASES
            : dataset.cases().stream().filter(item -> !item.expectedNoAnswer()).count();
        long noAnswerCases = dataset == null || dataset.cases() == null
            ? AUTO_NEGATIVE_CASES
            : dataset.cases().stream().filter(EvalCase::expectedNoAnswer).count();

        StringBuilder builder = new StringBuilder();
        builder.append("# ai-school-counselor-rag-eval 实验设计\n\n");
        builder.append("## 1. 实验目标\n");
        builder.append("- 验证高校辅导员知识库场景下，完整优化版 RAG 相比两类基线方案在检索效果与端到端回答质量上的改进。\n");
        builder.append("- 分离分析“召回能力提升”和“最终回答质量提升”之间的关系，避免只看生成结果而忽略检索链路贡献。\n");
        builder.append("- 观察 CRAG 兜底、混合检索、结构感知分块等模块对幻觉控制与拒答策略的实际影响。\n\n");

        builder.append("## 2. 对照组设置\n\n");
        builder.append("| 组别 | 检索方式 | 分块方式 | 排序/融合 | 生成策略 |\n");
        builder.append("| --- | --- | --- | --- | --- |\n");
        builder.append("| Baseline-A | BM25 关键词检索 | 原始线上 chunk | 无向量召回、无重排 | 检索后直接生成 |\n");
        builder.append("| Baseline-B | 单路向量检索 | 固定 512 字符重分块 | 无混合检索、无 CRAG | 朴素 RAG 生成 |\n");
        builder.append("| System | 混合检索 | 结构感知分块 | 意图路由 + 融合 + 重排 + CRAG | 结构化提示生成 |\n\n");

        builder.append("## 3. 语料与评测集\n");
        builder.append("- 语料来源：直接读取线上 MySQL 知识库，并复用系统当前 chunk 数据。\n");
        builder.append("- 当前快照统计：知识库 ").append(knowledgeCount).append(" 个，文档 ").append(documentCount)
            .append(" 篇，chunk ").append(chunkCount).append(" 条。\n");
        builder.append("- 数据集名称：`ai-school-counselor-rag-eval`。\n");
        builder.append("- 评测集规模：").append(totalCases).append(" 条，其中可回答样本 ").append(answerableCases)
            .append(" 条，无答案样本 ").append(noAnswerCases).append(" 条。\n");
        builder.append("- 正样本构建：从高质量真实 chunk 中进行分层抽样，调用大模型弱监督生成问题与标准答案，并保留 chunk 级证据标注。\n");
        builder.append("- 负样本构建：人工设计明显超出高校辅导员知识库边界的问题，用于检验系统拒答与兜底能力。\n");
        builder.append("- 正式论文建议：先在 `manual_eval_dataset.json` 上人工复核，再跑最终结果。\n\n");

        builder.append("## 4. 评价指标\n");
        builder.append("- 检索指标：HitRate、MRR、Recall、Precision。\n");
        builder.append("- 生成指标：Correctness、Faithfulness、Relevance。\n");
        builder.append("- 稳健性指标：Answer Accuracy、Hallucination Rate、Fallback Success Rate。\n");
        builder.append("- 评审方式：结合问题、标准答案、检索证据与系统回答，由 LLM 评审器输出结构化分数，再统计各组均值。\n\n");

        builder.append("## 5. 实验流程\n");
        builder.append("1. 运行 `exportCorpusSnapshot()` 导出知识库快照与人工标注模板。\n");
        builder.append("2. 运行 `buildAutoEvaluationDataset()` 生成 200+ 条自动评测集。\n");
        builder.append("3. 抽查并修订 `manual_eval_dataset.json` 中的问题、答案和无答案标签。\n");
        builder.append("4. 运行 `runThreeWayComparison()` 执行 Baseline-A、Baseline-B、System 三组对照实验。\n");
        builder.append("5. 汇总输出 `comparison_report.md`、`comparison_result.json` 与 `comparison_result.csv`。\n\n");

        builder.append("## 6. 当前优化假设\n");
        builder.append("- 将 `min-acceptable-score` 下调，可减少相关片段在后处理阶段被过早过滤导致的错误拒答。\n");
        builder.append("- 提高单条参考片段长度与来源上限，可降低长规则、表格型制度文本在答案阶段被截断的信息损失。\n");
        builder.append("- 若优化后检索指标和 Correctness 同步提升，可证明当前系统瓶颈主要位于“证据保留不足”而非“检索框架设计失效”。\n");
        return builder.toString();
    }

    private String fileWithSuffix(String fileName, String suffix) {
        if (!StringUtils.hasText(suffix)) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return fileName + suffix;
        }
        return fileName.substring(0, dot) + suffix + fileName.substring(dot);
    }

    private Path outputDir() throws IOException {
        Path dir = Path.of(OUTPUT_FOLDER);
        Files.createDirectories(dir);
        return dir;
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        objectMapper.writeValue(path.toFile(), value);
    }

    private void writeString(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private JsonNode safeReadJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            return objectMapper.readTree(trimmed);
        } catch (Exception ignored) {
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return objectMapper.readTree(trimmed.substring(start, end + 1));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private double avg(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        return values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double ratio(long numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return numerator * 1.0 / denominator;
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private String format2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String format3(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private int clampScore(int score) {
        return Math.max(1, Math.min(5, score));
    }

    private String compactText(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxLength) {
            return compact;
        }
        return compact.substring(0, maxLength) + "...";
    }

    private String sanitizeSnippet(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replace("\u0000", "").trim();
    }

    private String cleanInline(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private String csv(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private enum ExperimentVariant {
        BASELINE_A("Baseline-A"),
        BASELINE_B("Baseline-B"),
        SYSTEM("System");

        private final String label;

        ExperimentVariant(String label) {
            this.label = label;
        }
    }

    private record KnowledgeSnapshot(Long id,
                                     String name,
                                     String description,
                                     Integer documentCount,
                                     Integer chunkCount) {
    }

    private record DocumentSnapshot(Long documentId,
                                    Long knowledgeId,
                                    String knowledgeName,
                                    String fileName,
                                    String chunkMode,
                                    Integer chunkCount,
                                    String fullTextPreview) {
    }

    private record EvalChunk(Long documentId,
                             Long knowledgeId,
                             String knowledgeName,
                             String fileName,
                             Integer fragmentIndex,
                             String text,
                             String md5Hash) {
    }

    private record CorpusSnapshot(String generatedAt,
                                  List<KnowledgeSnapshot> knowledge,
                                  List<DocumentSnapshot> documents,
                                  List<EvalChunk> chunks) {
    }

    private record ChunkRef(Long documentId, Integer fragmentIndex) {
    }

    private record EvalCase(String query,
                            String referenceAnswer,
                            String questionType,
                            String difficulty,
                            String knowledgeScope,
                            List<ChunkRef> relevantChunks,
                            boolean expectedNoAnswer,
                            String sourceFileName,
                            List<String> keywords,
                            String sourceType) {
    }

    private record EvalDataset(String datasetName,
                               String generatedAt,
                               String note,
                               List<EvalCase> cases) {
    }

    private record VariantRunResult(String answer,
                                    List<RetrievalMatch> retrievalMatches,
                                    int retrievalTopK,
                                    String cragAction) {
    }

    private record RetrievedChunk(Long documentId,
                                  Integer fragmentIndex,
                                  String fileName,
                                  Double score,
                                  String snippet) {
    }

    private record RetrievalMetrics(double hitRate,
                                    double mrr,
                                    double recall,
                                    double precision) {
    }

    private record JudgeResult(int correctness,
                               int faithfulness,
                               int relevance,
                               boolean fallbackAppropriate,
                               boolean hallucination,
                               String issueType,
                               String summary) {
    }

    private record CaseRunRecord(String variant,
                                 String question,
                                 boolean expectedNoAnswer,
                                 String questionType,
                                 String difficulty,
                                 String knowledgeScope,
                                 String answer,
                                 String cragAction,
                                 int retrievalTopK,
                                 List<RetrievedChunk> retrievedChunks,
                                 RetrievalMetrics retrieval,
                                 JudgeResult judge) {
    }

    private record VariantSummary(String variant,
                                  String label,
                                  double hitRate,
                                  double mrr,
                                  double recall,
                                  double precision,
                                  double correctness,
                                  double faithfulness,
                                  double relevance,
                                  double answerAccuracy,
                                  double hallucinationRate,
                                  double fallbackSuccessRate,
                                  int sampleCount) {
    }

    private record ComparisonResult(String datasetName,
                                    String finishedAt,
                                    String outputDir,
                                    List<VariantSummary> summaries,
                                    List<CaseRunRecord> details) {
    }

    private record BaselineBChunk(Long documentId,
                                  String md5Hash,
                                  Integer fragmentIndex,
                                  String text,
                                  String fileName,
                                  String knowledgeName) {
    }

    private record BaselineBChunkVector(BaselineBChunk chunk, float[] vector) {
    }

    private SmartRetrieverService getSmartRetrieverService() {
        return smartRetrieverServiceProvider.getObject();
    }

    private RagPromptService getRagPromptService() {
        return ragPromptServiceProvider.getObject();
    }

    private IntentResolutionService getIntentResolutionService() {
        return intentResolutionServiceProvider.getObject();
    }

    private SubQueryRetrievalService getSubQueryRetrievalService() {
        return subQueryRetrievalServiceProvider.getObject();
    }

    private RetrievalPostProcessorService getRetrievalPostProcessorService() {
        return retrievalPostProcessorServiceProvider.getObject();
    }

    private final class BaselineBIndex {

        private final List<BaselineBChunkVector> vectors;

        private BaselineBIndex(List<BaselineBChunkVector> vectors) {
            this.vectors = vectors == null ? List.of() : List.copyOf(vectors);
        }

        private List<RetrievalMatch> search(String query, int topK) {
            if (!StringUtils.hasText(query) || vectors.isEmpty()) {
                return List.of();
            }
            List<float[]> queryVectors = vectorEncoding.encode(List.of(query));
            if (queryVectors == null || queryVectors.isEmpty()) {
                return List.of();
            }
            float[] queryVector = queryVectors.get(0);
            return vectors.stream()
                .map(item -> toMatch(item, queryVector))
                .sorted((left, right) -> Double.compare(
                    right.getRelevanceScore() == null ? 0.0 : right.getRelevanceScore(),
                    left.getRelevanceScore() == null ? 0.0 : left.getRelevanceScore()
                ))
                .limit(topK)
                .toList();
        }

        private RetrievalMatch toMatch(BaselineBChunkVector item, float[] queryVector) {
            BaselineBChunk chunk = item.chunk();
            double score = VectorMathUtils.cosine(queryVector, item.vector());
            RetrievalMatch match = new RetrievalMatch(
                chunk.md5Hash(),
                chunk.fragmentIndex(),
                chunk.text(),
                score
            );
            match.setDocumentId(chunk.documentId());
            match.setSourceFileName(chunk.fileName());
            return match;
        }
    }
}
