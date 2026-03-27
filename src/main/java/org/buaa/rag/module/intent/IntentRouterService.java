package org.buaa.rag.module.intent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.dto.IntentClassifyResult;
import org.buaa.rag.dto.IntentDecision;
import org.buaa.rag.dto.IntentNode;
import org.buaa.rag.tool.LlmChat;
import org.buaa.rag.module.index.VectorEncoding;
import org.buaa.rag.properties.IntentGuidanceProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class IntentRouterService {

    private static final double SEMANTIC_DIRECT_THRESHOLD = 0.9;
    private static final double LLM_RAG_THRESHOLD = 0.5;

    private static final String INTENT_PROMPT = PromptTemplateLoader.load("intent-router.st", """
你是高校辅导员对话的意图分类器。根据用户问题输出 JSON：
{
  "level1": "学业指导|办事指南|心理与生活|日常闲聊|其他",
  "level2": "具体意图",
  "confidence": 0.0-1.0,
  "toolName": "leave|repair|score|weather|none",
  "clarify": "若不确定，给出一句澄清问题，否则留空"
}
仅输出 JSON。
""");
    private static final String TREE_CLASSIFIER_PROMPT = PromptTemplateLoader.load("intent-tree-classifier.st", """
你是树形意图分类器。只输出 JSON 数组，每项包含 nodeId、score、reason。
""");

    private static final Set<String> CRISIS_KEYWORDS = Set.of(
        "自杀",
        "轻生",
        "跳楼",
        "抑郁太难了",
        "不想活了",
        "不想活",
        "活着没意义"
    );
    private static final Map<String, String> TOOL_KEYWORDS = Map.of(
        "请假", "leave",
        "销假", "leave",
        "报修", "repair",
        "成绩", "score",
        "绩点", "score",
        "天气", "weather",
        "气温", "weather",
        "降雨", "weather",
        "下雨", "weather"
    );
    private static final float[] EMPTY_VECTOR = new float[0];

    private final LlmChat llmChat;
    private final IntentPatternService intentPatternService;
    private final IntentTreeService intentTreeService;
    private final VectorEncoding vectorEncoding;
    private final IntentGuidanceProperties intentGuidanceProperties;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, float[]> nodeEmbeddingCache = new ConcurrentHashMap<>();

    public IntentRouterService(LlmChat llmChat,
                               IntentPatternService intentPatternService,
                               IntentTreeService intentTreeService,
                               VectorEncoding vectorEncoding,
                               IntentGuidanceProperties intentGuidanceProperties,
                               ObjectProvider<ChatClient.Builder> chatClientBuilderProvider) {
        this.llmChat = llmChat;
        this.intentPatternService = intentPatternService;
        this.intentTreeService = intentTreeService;
        this.vectorEncoding = vectorEncoding;
        this.intentGuidanceProperties = intentGuidanceProperties;
        this.chatClientBuilderProvider = chatClientBuilderProvider;
    }

    public IntentDecision decide(String userId, String query) {
        if (query == null || query.isBlank()) {
            return IntentDecision.builder()
                .action(IntentDecision.Action.CLARIFY)
                .clarifyQuestion("请提供具体问题")
                .build();
        }

        // 1) 危机规则直接拦截
        if (containsAny(query, CRISIS_KEYWORDS)) {
            return IntentDecision.builder()
                .action(IntentDecision.Action.CRISIS)
                .level1("心理与生活")
                .level2("危机干预")
                .clarifyQuestion("如需帮助请立即联系辅导员，是否需要电话？")
                .confidence(1.0)
                .strategy(IntentDecision.Strategy.CLARIFY_ONLY)
                .build();
        }

        // 2) 关键词轻量工具路由
        for (Map.Entry<String, String> entry : TOOL_KEYWORDS.entrySet()) {
            if (query.contains(entry.getKey())) {
                return IntentDecision.builder()
                    .action(IntentDecision.Action.ROUTE_TOOL)
                    .toolName(entry.getValue())
                    .level1("办事指南")
                    .level2(entry.getValue())
                    .confidence(0.92)
                    .strategy(IntentDecision.Strategy.PRECISION)
                    .build();
            }
        }

        // 3) 意图树候选打分 + 歧义引导
        IntentDecision treeDecision = routeByTree(query);
        if (treeDecision != null
            && treeDecision.getConfidence() != null
            && treeDecision.getConfidence() >= intentGuidanceProperties.getHitThreshold()) {
            return treeDecision;
        }

        // 4) 语义路由（ES + 向量）
        var semantic = intentPatternService.semanticRoute(query);
        if (semantic.isPresent()) {
            IntentDecision hit = semantic.get();
            if (hit.getConfidence() != null && hit.getConfidence() >= SEMANTIC_DIRECT_THRESHOLD) {
                hit.setStrategy(pickStrategy(hit.getLevel2(), hit.getToolName()));
                return hit;
            }
            // 0.7~0.9 走 LLM 兜底复核
        }

        // 5) 语义/LLM 分类兜底
        IntentDecision llmDecision = classifyWithLlm(query);
        if (llmDecision.getAction() == IntentDecision.Action.ROUTE_TOOL
            || (llmDecision.getConfidence() != null && llmDecision.getConfidence() >= LLM_RAG_THRESHOLD)) {
            return llmDecision;
        }

        // 6) 低置信度 -> 澄清
        return IntentDecision.builder()
            .action(IntentDecision.Action.CLARIFY)
            .clarifyQuestion(llmDecision.getClarifyQuestion() != null
                ? llmDecision.getClarifyQuestion() : "想了解的具体场景是？")
            .strategy(IntentDecision.Strategy.CLARIFY_ONLY)
            .build();
    }

    /**
     * 返回可排序的意图候选列表（用于多意图解析与并行检索）。
     */
    public List<IntentDecision> rankIntentCandidates(String userId, String query, int topN, double minScore) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        intentTreeService.loadTree();
        var rootOpt = intentTreeService.root();
        if (rootOpt.isEmpty()) {
            return List.of();
        }
        List<IntentNode> leaves = collectLeafNodes(rootOpt.get());
        if (leaves.isEmpty()) {
            return List.of();
        }

        List<IntentCandidate> candidates = classifyByLlm(query, leaves);
        if (candidates.isEmpty()) {
            candidates = collectLeafCandidatesBySimilarity(leaves, query);
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        int max = Math.max(1, topN);
        double scoreFloor = Math.max(0.0, minScore);
        List<IntentDecision> decisions = new ArrayList<>();
        for (IntentCandidate candidate : candidates) {
            if (candidate.score() < scoreFloor) {
                continue;
            }
            IntentDecision decision = toDecision(candidate, query);
            if (decision == null || decision.getAction() == IntentDecision.Action.CLARIFY) {
                continue;
            }
            decisions.add(decision);
            if (decisions.size() >= max) {
                break;
            }
        }
        return decisions;
    }

    private IntentDecision classifyWithLlm(String query) {
        IntentDecision structured = classifyWithSpringAiStructured(query);
        if (structured != null) {
            return structured;
        }

        String output = llmChat.generateCompletion(INTENT_PROMPT, query, 256);
        if (output == null || output.isBlank()) {
            return IntentDecision.builder()
                .action(IntentDecision.Action.ROUTE_RAG)
                .confidence(0.0)
                .build();
        }

        try {
            IntentClassifyResult result = objectMapper.readValue(output.trim(), IntentClassifyResult.class);
            return toDecision(result, query);
        } catch (Exception e) {
            log.debug("LLM意图解析失败: {}", e.getMessage());
            return IntentDecision.builder()
                .action(IntentDecision.Action.ROUTE_RAG)
                .confidence(0.0)
                .strategy(IntentDecision.Strategy.HYBRID)
                .build();
        }
    }

    private IntentDecision classifyWithSpringAiStructured(String query) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            return null;
        }

        try {
            BeanOutputConverter<IntentClassifyResult> converter =
                new BeanOutputConverter<>(IntentClassifyResult.class);
            String systemPrompt = INTENT_PROMPT + "\n输出格式要求：\n" + converter.getFormat();

            IntentClassifyResult result = builder.build()
                .prompt()
                .system(systemPrompt)
                .user(query)
                .options(ChatOptions.builder().temperature(0.1).maxTokens(256).build())
                .call()
                .entity(converter);

            if (result == null) {
                return null;
            }
            return toDecision(result, query);
        } catch (Exception e) {
            log.debug("Spring AI 结构化分类失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean containsAny(String text, Set<String> keywords) {
        if (text == null || CollectionUtils.isEmpty(keywords)) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private IntentDecision.Strategy pickStrategy(String level2, String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            return IntentDecision.Strategy.PRECISION;
        }
        if (level2 != null && (level2.contains("流程") || level2.contains("请假") || level2.contains("报修"))) {
            return IntentDecision.Strategy.PRECISION;
        }
        return IntentDecision.Strategy.HYBRID;
    }

    /**
     * 意图树候选路由：
     * 1. 对叶子节点统一打分
     * 2. 识别多候选歧义并触发澄清
     * 3. 选择最高分候选落地动作
     */
    private IntentDecision routeByTree(String query) {
        intentTreeService.loadTree();
        var rootOpt = intentTreeService.root();
        if (rootOpt.isEmpty()) {
            return null;
        }

        List<IntentNode> leaves = collectLeafNodes(rootOpt.get());
        if (leaves.isEmpty()) {
            return null;
        }

        List<IntentCandidate> candidates = classifyByLlm(query, leaves);
        if (candidates.isEmpty()) {
            candidates = collectLeafCandidatesBySimilarity(leaves, query);
        }
        if (candidates.isEmpty()) {
            return null;
        }

        if (intentGuidanceProperties.isEnabled() && needsGuidance(candidates)) {
            return buildGuidanceDecision(candidates);
        }
        return toDecision(candidates.get(0), query);
    }

    private List<IntentCandidate> classifyByLlm(String query, List<IntentNode> leaves) {
        if (!StringUtils.hasText(query) || leaves == null || leaves.isEmpty()) {
            return List.of();
        }
        Map<String, IntentNode> leafMap = leaves.stream()
            .filter(node -> node != null && node.getNodeId() != null)
            .collect(Collectors.toMap(IntentNode::getNodeId, node -> node, (left, right) -> left));
        if (leafMap.isEmpty()) {
            return List.of();
        }

        String userPrompt = buildTreeClassifyUserPrompt(query, leaves);
        String raw = llmChat.generateCompletion(TREE_CLASSIFIER_PROMPT, userPrompt, 512);
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        List<TreeClassifyResult> parsed = parseTreeClassifyResults(raw);
        if (parsed.isEmpty()) {
            return List.of();
        }
        double minScore = intentGuidanceProperties.getLlmMinScore();
        int maxCandidates = Math.max(1, intentGuidanceProperties.getMaxCandidates());
        List<IntentCandidate> candidates = new ArrayList<>();
        for (TreeClassifyResult result : parsed) {
            if (result == null || !StringUtils.hasText(result.nodeId())) {
                continue;
            }
            IntentNode node = leafMap.get(result.nodeId().trim());
            if (node == null) {
                continue;
            }
            double score = clampScore(result.score());
            if (score < minScore) {
                continue;
            }
            candidates.add(new IntentCandidate(node, score));
        }
        candidates.sort((left, right) -> Double.compare(right.score(), left.score()));
        if (candidates.size() > maxCandidates) {
            return candidates.subList(0, maxCandidates);
        }
        return candidates;
    }

    private List<IntentCandidate> collectLeafCandidatesBySimilarity(List<IntentNode> leaves, String query) {
        if (leaves == null || leaves.isEmpty()) {
            return List.of();
        }
        float[] queryVector = encodeSingle(query);
        List<IntentCandidate> candidates = new ArrayList<>(leaves.size());
        for (IntentNode leaf : leaves) {
            double score = similarity(query, queryVector, leaf);
            candidates.add(new IntentCandidate(leaf, score));
        }
        candidates.sort((left, right) -> Double.compare(right.score(), left.score()));
        int maxCandidates = Math.max(1, intentGuidanceProperties.getMaxCandidates());
        double hitThreshold = intentGuidanceProperties.getHitThreshold();
        return candidates.stream()
            .filter(candidate -> candidate.score() >= hitThreshold)
            .limit(maxCandidates)
            .toList();
    }

    private List<IntentNode> collectLeafNodes(IntentNode root) {
        Deque<IntentNode> stack = new ArrayDeque<>();
        stack.push(root);
        List<IntentNode> leaves = new ArrayList<>();

        while (!stack.isEmpty()) {
            IntentNode node = stack.pop();
            List<IntentNode> children = intentTreeService.children(node.getNodeId());
            if (children == null || children.isEmpty()) {
                leaves.add(node);
                continue;
            }
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        return leaves;
    }

    private boolean needsGuidance(List<IntentCandidate> candidates) {
        if (candidates == null || candidates.size() < 2) {
            return false;
        }
        IntentCandidate best = candidates.get(0);
        IntentCandidate second = candidates.get(1);
        if (best.score() <= 0) {
            return false;
        }
        double ratio = second.score() / best.score();
        if (ratio < intentGuidanceProperties.getAmbiguityRatio()) {
            return false;
        }
        return extractGuidanceOptions(candidates).size() > 1;
    }

    private IntentDecision buildGuidanceDecision(List<IntentCandidate> candidates) {
        IntentDecision sameTopicGuidance = buildSameTopicGuidance(candidates);
        if (sameTopicGuidance != null) {
            return sameTopicGuidance;
        }
        List<String> options = extractGuidanceOptions(candidates);
        if (options.isEmpty()) {
            return null;
        }
        String clarify = "你想咨询哪个方向？"
            + options.stream()
            .limit(Math.max(1, intentGuidanceProperties.getMaxOptions()))
            .map(option -> "\n- " + option)
            .collect(Collectors.joining(""));
        return IntentDecision.builder()
            .action(IntentDecision.Action.CLARIFY)
            .clarifyQuestion(clarify)
            .confidence(candidates.get(0).score())
            .strategy(IntentDecision.Strategy.CLARIFY_ONLY)
            .build();
    }

    /**
     * 专项歧义：同主题节点在不同域下同时高分，优先给出“域 + 主题”的澄清选项。
     */
    private IntentDecision buildSameTopicGuidance(List<IntentCandidate> candidates) {
        if (candidates == null || candidates.size() < 2) {
            return null;
        }
        IntentCandidate top = candidates.get(0);
        if (top.score() <= 0) {
            return null;
        }
        String normalizedTopic = normalizeName(top.node().getNodeName());
        if (!StringUtils.hasText(normalizedTopic)) {
            return null;
        }
        List<String> options = new ArrayList<>();
        for (IntentCandidate candidate : candidates) {
            if (candidate.score() < top.score() * intentGuidanceProperties.getAmbiguityRatio()) {
                continue;
            }
            String candidateTopic = normalizeName(candidate.node().getNodeName());
            if (!normalizedTopic.equals(candidateTopic)) {
                continue;
            }
            String domain = resolveDomainName(candidate.node());
            String option = StringUtils.hasText(domain)
                ? domain + " - " + candidate.node().getNodeName()
                : candidate.node().getNodeName();
            if (!options.contains(option)) {
                options.add(option);
            }
        }
        if (options.size() < 2) {
            return null;
        }
        String clarify = "你提到的是同名主题，我先确认下具体系统："
            + options.stream()
            .limit(Math.max(1, intentGuidanceProperties.getMaxOptions()))
            .map(option -> "\n- " + option)
            .collect(Collectors.joining(""));
        return IntentDecision.builder()
            .action(IntentDecision.Action.CLARIFY)
            .clarifyQuestion(clarify)
            .confidence(top.score())
            .strategy(IntentDecision.Strategy.CLARIFY_ONLY)
            .build();
    }

    private List<String> extractGuidanceOptions(List<IntentCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        double topScore = candidates.get(0).score();
        LinkedHashSet<String> options = new LinkedHashSet<>();
        for (IntentCandidate candidate : candidates) {
            if (candidate.score() < topScore * intentGuidanceProperties.getAmbiguityRatio()) {
                continue;
            }
            String domain = resolveDomainName(candidate.node());
            if (domain != null && !domain.isBlank()) {
                options.add(domain);
            }
        }
        return new ArrayList<>(options);
    }

    private double similarity(String query, float[] queryVector, IntentNode node) {
        double keywordScore = keywordScore(query, node);
        double semanticScore = semanticScore(queryVector, node);

        // 关键词优先，语义补充，最终压到 [0,1]
        double blended = keywordScore + semanticScore * 0.7;
        return Math.max(0.0, Math.min(1.0, blended));
    }

    private double keywordScore(String query, IntentNode node) {
        if (query == null || node == null || node.getKeywords() == null || node.getKeywords().isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        for (String keyword : node.getKeywords()) {
            if (keyword != null && !keyword.isBlank() && query.contains(keyword)) {
                score += 0.25;
            }
        }
        return Math.min(0.6, score);
    }

    private double semanticScore(float[] queryVector, IntentNode node) {
        if (queryVector == null || queryVector.length == 0 || node == null) {
            return 0.0;
        }

        String description = node.getDescription();
        if (description == null || description.isBlank()) {
            return 0.0;
        }

        float[] nodeVector = nodeEmbeddingCache.computeIfAbsent(node.getNodeId(), key -> {
            try {
                List<float[]> vectors = vectorEncoding.encode(List.of(description));
                if (vectors == null || vectors.isEmpty() || vectors.get(0) == null) {
                    return EMPTY_VECTOR;
                }
                return vectors.get(0);
            } catch (Exception e) {
                log.debug("节点向量计算失败, nodeId={}, error={}", node.getNodeId(), e.getMessage());
                return EMPTY_VECTOR;
            }
        });

        if (nodeVector.length == 0) {
            return 0.0;
        }

        return cosine(queryVector, nodeVector);
    }

    private float[] encodeSingle(String text) {
        try {
            List<float[]> vectors = vectorEncoding.encode(List.of(text));
            if (vectors == null || vectors.isEmpty()) {
                return null;
            }
            return vectors.get(0);
        } catch (Exception e) {
            log.debug("查询向量计算失败，回退关键词路由: {}", e.getMessage());
            return null;
        }
    }

    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        double cos = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        if (Double.isNaN(cos) || Double.isInfinite(cos)) {
            return 0.0;
        }

        // [-1,1] -> [0,1]
        return Math.max(0.0, Math.min(1.0, (cos + 1.0) / 2.0));
    }

    private IntentDecision toDecision(IntentCandidate cand, String query) {
        IntentNode node = cand.node();
        List<IntentNode> children = intentTreeService.children(node.getNodeId());
        if (node.getType() == IntentNode.NodeType.GROUP && children != null && !children.isEmpty()) {
            String options = children.stream()
                .limit(3)
                .map(IntentNode::getNodeName)
                .collect(Collectors.joining("、"));
            return IntentDecision.builder()
                .action(IntentDecision.Action.CLARIFY)
                .clarifyQuestion("你想了解的是 " + options + " 中的哪一项？")
                .confidence(cand.score())
                .strategy(IntentDecision.Strategy.CLARIFY_ONLY)
                .build();
        }

        IntentDecision.Action action = switch (node.getType()) {
            case API_ACTION -> IntentDecision.Action.ROUTE_TOOL;
            case CHITCHAT -> IntentDecision.Action.ROUTE_RAG;
            default -> IntentDecision.Action.ROUTE_RAG;
        };
        String tool = node.getType() == IntentNode.NodeType.API_ACTION
            ? normalizeToolName(node.getActionService())
            : null;
        String level1 = resolveDomainName(node);
        String level2 = node.getNodeName();
        return IntentDecision.builder()
            .level1(level1)
            .level2(level2)
            .promptTemplate(node.getPromptTemplate())
            .knowledgeBaseId(node.getKnowledgeBaseId())
            .toolName(tool)
            .action(action)
            .confidence(cand.score())
            .strategy(pickStrategy(node.getNodeName(), tool))
            .clarifyQuestion(null)
            .build();
    }

    private String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private String buildTreeClassifyUserPrompt(String query, List<IntentNode> leaves) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户问题：").append(query).append("\n\n");
        builder.append("候选叶子节点列表：\n");
        for (IntentNode leaf : leaves) {
            builder.append("- nodeId=").append(leaf.getNodeId()).append("\n");
            builder.append("  path=").append(resolveNodePath(leaf)).append("\n");
            if (StringUtils.hasText(leaf.getDescription())) {
                builder.append("  description=").append(leaf.getDescription().trim()).append("\n");
            }
            if (leaf.getKeywords() != null && !leaf.getKeywords().isEmpty()) {
                builder.append("  keywords=").append(String.join(" / ", leaf.getKeywords())).append("\n");
            }
        }
        return builder.toString();
    }

    private List<TreeClassifyResult> parseTreeClassifyResults(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String content = raw.trim();
        try {
            if (content.startsWith("```")) {
                content = content.replace("```json", "").replace("```", "").trim();
            }
            if (content.startsWith("[")) {
                return objectMapper.readValue(content, new TypeReference<List<TreeClassifyResult>>() {
                });
            }
            TreeClassifyWrapper wrapped = objectMapper.readValue(content, TreeClassifyWrapper.class);
            if (wrapped == null || wrapped.results() == null) {
                return List.of();
            }
            return wrapped.results();
        } catch (Exception e) {
            log.debug("意图树LLM候选解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private double clampScore(Double score) {
        if (score == null) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    private String resolveNodePath(IntentNode node) {
        if (node == null) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        IntentNode current = node;
        int guard = 0;
        while (current != null && guard++ < 10) {
            if (StringUtils.hasText(current.getNodeName())) {
                segments.add(0, current.getNodeName().trim());
            }
            if (!StringUtils.hasText(current.getParentId())) {
                break;
            }
            current = intentTreeService.getById(current.getParentId()).orElse(null);
        }
        return String.join(" > ", segments);
    }

    private String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        return name.trim().toLowerCase().replaceAll("[\\p{Punct}\\s]+", "");
    }

    private String normalizeToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String normalized = toolName.trim();
        if ("weather_query".equalsIgnoreCase(normalized) || "queryWeatherByMcp".equalsIgnoreCase(normalized)) {
            return "weather";
        }
        if ("none".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private IntentDecision toDecision(IntentClassifyResult result, String query) {
        String level1 = normalizeText(result.getLevel1());
        String level2 = normalizeText(result.getLevel2());
        double confidence = result.getConfidence() == null ? 0.0 : result.getConfidence();
        String toolName = normalizeToolName(result.getToolName());
        String clarify = normalizeText(result.getClarify());

        IntentDecision.Action action;
        if (toolName != null) {
            action = IntentDecision.Action.ROUTE_TOOL;
        } else if (confidence >= LLM_RAG_THRESHOLD) {
            action = IntentDecision.Action.ROUTE_RAG;
        } else {
            action = IntentDecision.Action.CLARIFY;
        }

        return IntentDecision.builder()
            .level1(level1)
            .level2(level2)
            .confidence(confidence)
            .toolName(toolName)
            .clarifyQuestion(action == IntentDecision.Action.CLARIFY
                ? (clarify != null ? clarify : "你想咨询哪一类具体事项？")
                : null)
            .action(action)
            .strategy(pickStrategy(level2, toolName))
            .build();
    }

    private String resolveDomainName(IntentNode node) {
        if (node == null) {
            return null;
        }
        IntentNode current = node;
        IntentNode domain = node;
        while (current != null) {
            String parentId = current.getParentId();
            if (parentId == null || parentId.isBlank()) {
                break;
            }
            IntentNode parent = intentTreeService.getById(parentId).orElse(null);
            if (parent == null) {
                break;
            }
            if ("root".equalsIgnoreCase(parent.getNodeId())) {
                domain = current;
                break;
            }
            current = parent;
        }
        return domain.getNodeName();
    }

    private record IntentCandidate(IntentNode node, double score) {
    }

    private record TreeClassifyResult(String nodeId, Double score, String reason) {
    }

    private record TreeClassifyWrapper(List<TreeClassifyResult> results) {
    }
}
