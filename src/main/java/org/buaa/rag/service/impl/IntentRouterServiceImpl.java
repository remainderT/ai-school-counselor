package org.buaa.rag.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.dto.IntentClassifyResult;
import org.buaa.rag.dto.IntentDecision;
import org.buaa.rag.dto.IntentNode;
import org.buaa.rag.service.IntentPatternService;
import org.buaa.rag.service.IntentRouterService;
import org.buaa.rag.service.IntentTreeService;
import org.buaa.rag.tool.LlmChat;
import org.buaa.rag.tool.VectorEncoding;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class IntentRouterServiceImpl implements IntentRouterService {

    private static final int TREE_BEAM_WIDTH = 2;
    private static final int TREE_MAX_DEPTH = 4;
    private static final double TREE_HIT_THRESHOLD = 0.6;
    private static final double TREE_CLARIFY_GAP = 0.1;
    private static final double SEMANTIC_DIRECT_THRESHOLD = 0.9;
    private static final double LLM_RAG_THRESHOLD = 0.5;

    private static final String INTENT_PROMPT = PromptTemplateLoader.load("intent-router.st", """
你是高校辅导员对话的意图分类器。根据用户问题输出 JSON：
{
  "level1": "学业指导|办事指南|心理与生活|日常闲聊|其他",
  "level2": "具体意图",
  "confidence": 0.0-1.0,
  "toolName": "leave|repair|score|none",
  "clarify": "若不确定，给出一句澄清问题，否则留空"
}
仅输出 JSON。
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
        "绩点", "score"
    );
    private static final float[] EMPTY_VECTOR = new float[0];

    private final LlmChat llmChat;
    private final IntentPatternService intentPatternService;
    private final IntentTreeService intentTreeService;
    private final VectorEncoding vectorEncoding;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, float[]> nodeEmbeddingCache = new ConcurrentHashMap<>();

    public IntentRouterServiceImpl(LlmChat llmChat,
                                   IntentPatternService intentPatternService,
                                   IntentTreeService intentTreeService,
                                   VectorEncoding vectorEncoding,
                                   ObjectProvider<ChatClient.Builder> chatClientBuilderProvider) {
        this.llmChat = llmChat;
        this.intentPatternService = intentPatternService;
        this.intentTreeService = intentTreeService;
        this.vectorEncoding = vectorEncoding;
        this.chatClientBuilderProvider = chatClientBuilderProvider;
    }

    @Override
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

        // 3) 意图树 Beam 路由
        IntentDecision treeDecision = routeByTree(query);
        if (treeDecision != null
            && treeDecision.getConfidence() != null
            && treeDecision.getConfidence() >= TREE_HIT_THRESHOLD) {
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
     * 层级 Beam 路由：从根节点开始按相似度 Top-K 下钻
     */
    private IntentDecision routeByTree(String query) {
        intentTreeService.loadTree();
        var rootOpt = intentTreeService.root();
        if (rootOpt.isEmpty()) {
            return null;
        }

        float[] queryVector = encodeSingle(query);
        List<IntentCandidate> frontier = List.of(new IntentCandidate(rootOpt.get(), 1.0));
        int depth = 0;
        IntentCandidate bestLeaf = null;
        IntentCandidate secondLeaf = null;

        while (!frontier.isEmpty() && depth < TREE_MAX_DEPTH) {
            List<IntentCandidate> next = new ArrayList<>();
            for (IntentCandidate cand : frontier) {
                List<IntentNode> children = intentTreeService.children(cand.node().getNodeId());
                if (children == null || children.isEmpty()) {
                    if (bestLeaf == null || cand.score() > bestLeaf.score()) {
                        secondLeaf = bestLeaf;
                        bestLeaf = cand;
                    } else if (secondLeaf == null || cand.score() > secondLeaf.score()) {
                        secondLeaf = cand;
                    }
                    continue;
                }
                for (IntentNode child : children) {
                    double score = similarity(query, queryVector, child);
                    next.add(new IntentCandidate(child, score * cand.score()));
                }
            }

            if (next.isEmpty()) {
                break;
            }
            next.sort((a, b) -> Double.compare(b.score(), a.score()));
            int beam = Math.min(TREE_BEAM_WIDTH, next.size());
            frontier = next.subList(0, beam);
            depth++;
        }

        if (bestLeaf == null && !frontier.isEmpty()) {
            List<IntentCandidate> sorted = new ArrayList<>(frontier);
            sorted.sort((a, b) -> Double.compare(b.score(), a.score()));
            bestLeaf = sorted.get(0);
            if (sorted.size() > 1) {
                secondLeaf = sorted.get(1);
            }
        }

        if (bestLeaf != null) {
            if (secondLeaf != null && Math.abs(bestLeaf.score() - secondLeaf.score()) < TREE_CLARIFY_GAP) {
                String clarify = "你想了解的是 " + bestLeaf.node().getNodeName()
                    + " 还是 " + secondLeaf.node().getNodeName() + "？";
                return IntentDecision.builder()
                    .action(IntentDecision.Action.CLARIFY)
                    .clarifyQuestion(clarify)
                    .confidence(Math.max(bestLeaf.score(), secondLeaf.score()))
                    .strategy(IntentDecision.Strategy.CLARIFY_ONLY)
                    .build();
            }
            return toDecision(bestLeaf, query);
        }
        return null;
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
        String tool = node.getType() == IntentNode.NodeType.API_ACTION ? node.getActionService() : null;
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

    private String normalizeToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String normalized = toolName.trim();
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
}
