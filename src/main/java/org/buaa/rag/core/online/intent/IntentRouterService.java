package org.buaa.rag.core.online.intent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.common.util.VectorMathUtils;
import org.buaa.rag.core.model.IntentClassifyResult;
import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.IntentNode;
import org.buaa.rag.tool.LlmChat;
import org.buaa.rag.core.offline.index.VectorEncoding;
import org.buaa.rag.properties.IntentGuidanceProperties;
import org.buaa.rag.properties.IntentRoutingProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class IntentRouterService {

    private static final String INTENT_PROMPT = PromptTemplateLoader.load("intent-router.st");
    private static final String TREE_CLASSIFIER_PROMPT = PromptTemplateLoader.load("intent-tree-classifier.st");
    private static final String GUIDANCE_PROMPT_FILE = "guidance-prompt.st";
    private static final float[] EMPTY_VECTOR = new float[0];

    private final LlmChat llmChat;
    private final IntentPatternService intentPatternService;
    private final IntentTreeService intentTreeService;
    private final VectorEncoding vectorEncoding;
    private final IntentGuidanceProperties intentGuidanceProperties;
    private final IntentRoutingProperties intentRoutingProperties;
    private final ObjectMapper objectMapper;
    private final Map<String, float[]> nodeEmbeddingCache = new ConcurrentHashMap<>();

    public IntentRouterService(LlmChat llmChat,
                               IntentPatternService intentPatternService,
                               IntentTreeService intentTreeService,
                               VectorEncoding vectorEncoding,
                               IntentGuidanceProperties intentGuidanceProperties,
                               IntentRoutingProperties intentRoutingProperties,
                               ObjectMapper objectMapper) {
        this.llmChat = llmChat;
        this.intentPatternService = intentPatternService;
        this.intentTreeService = intentTreeService;
        this.vectorEncoding = vectorEncoding;
        this.intentGuidanceProperties = intentGuidanceProperties;
        this.intentRoutingProperties = intentRoutingProperties;
        this.objectMapper = objectMapper;
    }

    public IntentDecision decide(String userId, String query) {
        if (query == null || query.isBlank()) {
            return IntentDecision.builder()
                .action(IntentDecision.Action.CLARIFY)
                .clarifyQuestion("请提供具体问题")
                .build();
        }

        // 1) 关键词轻量工具路由
        for (Map.Entry<String, String> entry : intentRoutingProperties.getToolKeywords().entrySet()) {
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

        // 2) 意图树候选打分 + 歧义引导
        IntentDecision treeDecision = routeByTree(query);
        if (treeDecision != null
            && treeDecision.getConfidence() != null
            && treeDecision.getConfidence() >= intentGuidanceProperties.getHitThreshold()) {
            return treeDecision;
        }

        // 3) 语义路由（ES + 向量）
        var semantic = intentPatternService.semanticRoute(query);
        if (semantic.isPresent()) {
            IntentDecision hit = semantic.get();
            if (hit.getConfidence() != null && hit.getConfidence() >= intentRoutingProperties.getSemanticDirectThreshold()) {
                hit.setStrategy(pickStrategy(hit.getLevel2(), hit.getToolName()));
                return hit;
            }
            // 0.7~0.9 走 LLM 兜底复核
        }

        // 4) 语义/LLM 分类兜底
        IntentDecision llmDecision = classifyWithLlm(query);
        if (llmDecision.getAction() == IntentDecision.Action.ROUTE_TOOL
            || (llmDecision.getConfidence() != null && llmDecision.getConfidence() >= intentRoutingProperties.getLlmRagThreshold())) {
            return llmDecision;
        }

        // 5) 低置信度 -> 澄清
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
        List<IntentNode> leaves = intentTreeService.leaves();
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
     * 1. 对叶子节点统一打分（叶子列表从缓存快照读取，无需每次 DFS）
     * 2. 识别多候选歧义并触发澄清（若问题已含系统名则跳过）
     * 3. 选择最高分候选落地动作
     */
    private IntentDecision routeByTree(String query) {
        intentTreeService.loadTree();

        List<IntentNode> leaves = intentTreeService.leaves();
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
            IntentDecision guidance = buildGuidanceDecision(query, candidates);
            if (guidance != null) {
                return guidance;
            }
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

    /**
     * 构建歧义引导决策。
     *
     * <p>若问题中已经包含候选系统名（如"教务系统"），说明用户意图已明确，
     * 无需再弹出引导提示，直接返回 {@code null} 让调用方走最高分候选。
     * 这与 ragent {@code IntentGuidanceService.shouldSkipGuidance()} 的逻辑一致。
     */
    private IntentDecision buildGuidanceDecision(String query, List<IntentCandidate> candidates) {
        List<String> options = extractGuidanceOptions(candidates);
        if (options.isEmpty()) {
            return null;
        }

        // G3: 若问题中已含候选系统名，跳过引导直接走最高分候选
        if (shouldSkipGuidance(query, options)) {
            log.debug("问题已含系统名，跳过歧义引导: query={}", query);
            return null;
        }

        IntentDecision sameTopicGuidance = buildSameTopicGuidance(candidates);
        if (sameTopicGuidance != null) {
            return sameTopicGuidance;
        }

        String clarify = buildGuidancePrompt(options);
        return IntentDecision.builder()
            .action(IntentDecision.Action.CLARIFY)
            .clarifyQuestion(clarify)
            .confidence(candidates.get(0).score())
            .strategy(IntentDecision.Strategy.CLARIFY_ONLY)
            .build();
    }

    /**
     * 若问题已包含候选域名（系统名）中的任意一个，则跳过引导。
     *
     * <p>比较时先 normalize（去标点/空白/小写），避免全角/半角等差异导致误判。
     */
    private boolean shouldSkipGuidance(String question, List<String> domainNames) {
        if (!StringUtils.hasText(question) || domainNames == null || domainNames.isEmpty()) {
            return false;
        }
        String normalizedQ = normalizeName(question);
        for (String name : domainNames) {
            if (!StringUtils.hasText(name) || name.length() < 2) {
                continue;
            }
            String normalizedName = normalizeName(name);
            if (normalizedName.length() < 2) {
                continue;
            }
            if (normalizedQ.contains(normalizedName)) {
                return true;
            }
        }
        return false;
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
        // 同名主题歧义使用专有提示语，直接拼装后包装进标准 guidance prompt
        List<String> limitedOptions = options.stream()
            .limit(Math.max(1, intentGuidanceProperties.getMaxOptions()))
            .toList();
        String optionsText = "你提到的是同名主题，我先确认下具体系统：\n"
            + limitedOptions.stream().map(option -> "- " + option).collect(Collectors.joining("\n"));
        String clarify = PromptTemplateLoader.render(GUIDANCE_PROMPT_FILE,
            Map.of("options", optionsText));
        if (!StringUtils.hasText(clarify)) {
            clarify = optionsText;
        }
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

    /**
     * 使用 {@code guidance-prompt.st} 模板构建歧义引导问句。
     *
     * <p>模板内容（G4）：
     * <pre>
     * 你想咨询哪个方向？
     * {options}
     * </pre>
     * 其中 {@code {options}} 将被替换为以 "- " 开头的选项列表。
     */
    private String buildGuidancePrompt(List<String> options) {
        if (options == null || options.isEmpty()) {
            return "你想咨询哪个方向？";
        }
        List<String> limited = options.stream()
            .limit(Math.max(1, intentGuidanceProperties.getMaxOptions()))
            .toList();
        String optionsText = limited.stream()
            .map(o -> "- " + o)
            .collect(Collectors.joining("\n"));
        String rendered = PromptTemplateLoader.render(GUIDANCE_PROMPT_FILE,
            Map.of("options", optionsText));
        return StringUtils.hasText(rendered) ? rendered : "你想咨询哪个方向？\n" + optionsText;
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

        return VectorMathUtils.cosine(queryVector, nodeVector);
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
        } else if (confidence >= intentRoutingProperties.getLlmRagThreshold()) {
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
