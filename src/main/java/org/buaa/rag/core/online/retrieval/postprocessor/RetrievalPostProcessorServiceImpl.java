package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.List;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.core.online.rerank.RoutingRerankService;
import org.buaa.rag.properties.LlmProperties;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.core.model.CragDecision;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.buaa.rag.core.trace.RagTraceNode;

/**
 * 检索后处理服务实现。
 * <p>
 * 包含 CRAG 检索质量评估；rerank 委托给 {@link RerankService}（路由式，支持独立模型 + LLM 降级）。
 */
@Slf4j
@Service
public class RetrievalPostProcessorServiceImpl {

    private static final String DEFAULT_CRAG_PROMPT = PromptTemplateLoader.load("retrieval-crag.st");

    private static final String DEFAULT_CLARIFY_PROMPT = PromptTemplateLoader.load("retrieval-clarify.st");
    private static final double CRAG_TEMPERATURE = 0.1D;
    private static final double CRAG_TOP_P = 0.3D;

    private final LlmChat llmChat;
    private final RagProperties ragProperties;
    private final LlmProperties llmProperties;
    private final RoutingRerankService rerankService;
    private final ObjectMapper objectMapper;

    public RetrievalPostProcessorServiceImpl(LlmChat llmChat,
                                             RagProperties ragProperties,
                                             LlmProperties llmProperties,
                                             RoutingRerankService rerankService,
                                             ObjectMapper objectMapper) {
        this.llmChat = llmChat;
        this.ragProperties = ragProperties;
        this.llmProperties = llmProperties;
        this.rerankService = rerankService;
        this.objectMapper = objectMapper;
    }

    @RagTraceNode(name = "crag-quality-evaluate", type = "CRAG_EVAL")
    public CragDecision evaluate(String query, List<RetrievalMatch> matches) {
        long start = System.nanoTime();
        RagProperties.Crag config = ragProperties.getCrag();
        if (config == null || !config.isEnabled()) {
            return new CragDecision(CragDecision.Action.ANSWER, null);
        }

        if (matches == null || matches.isEmpty()) {
            if (isLikelyAmbiguous(query)) {
                CragDecision decision = new CragDecision(CragDecision.Action.CLARIFY, buildClarifyQuestion(query, config));
                log.info("CRAG评估完成 | query='{}' | matches=0 | action={} | 耗时={}ms",
                    compact(query), decision.getAction(), elapsedMs(start));
                return decision;
            }
            CragDecision decision = new CragDecision(CragDecision.Action.NO_ANSWER, noResultMessage());
            log.info("CRAG评估完成 | query='{}' | matches=0 | action={} | 耗时={}ms",
                compact(query), decision.getAction(), elapsedMs(start));
            return decision;
        }

        if (config.isUseLlm() && shouldReviewWithLlm(matches, config)) {
            CragDecision decision = evaluateWithLlm(query, matches, config);
            if (decision != null) {
                log.info("CRAG评估完成 | query='{}' | matches={} | action={} | llmReview=true | 耗时={}ms",
                    compact(query), matches.size(), decision.getAction(), elapsedMs(start));
                return decision;
            }
        }

        if (isLowQuality(matches, config.getMinScore())) {
            CragDecision decision = new CragDecision(CragDecision.Action.REFINE, null);
            log.info("CRAG评估完成 | query='{}' | matches={} | action={} | llmReview=false | 耗时={}ms",
                compact(query), matches.size(), decision.getAction(), elapsedMs(start));
            return decision;
        }

        CragDecision decision = new CragDecision(CragDecision.Action.ANSWER, null);
        log.info("CRAG评估完成 | query='{}' | matches={} | action={} | llmReview=false | 耗时={}ms",
            compact(query), matches.size(), decision.getAction(), elapsedMs(start));
        return decision;
    }

    public String noResultMessage() {
        if (llmProperties != null && llmProperties.getPromptTemplate() != null) {
            String noResult = llmProperties.getPromptTemplate().getNoResultText();
            if (noResult != null && !noResult.isBlank()) {
                return noResult;
            }
        }
        return "暂无相关信息";
    }

    public List<RetrievalMatch> rerank(String query, List<RetrievalMatch> matches, int topK) {
        if (matches == null || matches.size() <= 1) {
            return matches;
        }
        RagProperties.Rerank config = ragProperties.getRerank();
        if (config == null || !config.isEnabled()) {
            return matches;
        }
        return rerankService.rerank(query, matches, topK);
    }

    private boolean isLowQuality(List<RetrievalMatch> matches, double minScore) {
        if (matches == null || matches.isEmpty()) {
            return true;
        }
        Double score = matches.get(0).getRelevanceScore();
        return score == null || score < minScore;
    }

    private boolean shouldReviewWithLlm(List<RetrievalMatch> matches, RagProperties.Crag config) {
        if (matches == null || matches.isEmpty()) {
            return true;
        }
        Double score = matches.get(0).getRelevanceScore();
        if (score == null) {
            return true;
        }
        return score < config.getMinScore() * 1.5;
    }

    private CragDecision evaluateWithLlm(String query,
                                         List<RetrievalMatch> matches,
                                         RagProperties.Crag config) {
        String prompt = DEFAULT_CRAG_PROMPT;

        StringBuilder content = new StringBuilder();
        content.append("问题：").append(query).append("\n");
        content.append("候选片段：\n");
        int reviewTopK = Math.min(config.getReviewTopK(), matches.size());
        for (int i = 0; i < reviewTopK; i++) {
            RetrievalMatch match = matches.get(i);
            content.append(i + 1)
                .append("|")
                .append(truncate(match.getTextContent(), 240))
                .append("\n");
        }

        String output = llmChat.generateCompletion(
            prompt,
            content.toString(),
            256,
            CRAG_TEMPERATURE,
            CRAG_TOP_P
        );
        if (output == null || output.isBlank()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(output.trim());
            String actionText = node.path("action").asText("").toUpperCase();
            CragDecision.Action action = parseAction(actionText);
            if (action == null) {
                return null;
            }
            String clarifyQuestion = node.path("clarifyQuestion").asText(null);
            if (action == CragDecision.Action.CLARIFY) {
                if (clarifyQuestion == null || clarifyQuestion.isBlank()) {
                    clarifyQuestion = buildClarifyQuestion(query, config);
                }
                return new CragDecision(action, clarifyQuestion);
            }
            if (action == CragDecision.Action.NO_ANSWER) {
                return new CragDecision(action, noResultMessage());
            }
            return new CragDecision(action, null);
        } catch (Exception e) {
            log.debug("CRAG解析失败: {}", e.getMessage());
            return null;
        }
    }

    private CragDecision.Action parseAction(String actionText) {
        if (actionText == null || actionText.isBlank()) {
            return null;
        }
        try {
            return CragDecision.Action.valueOf(actionText);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String buildClarifyQuestion(String query, RagProperties.Crag config) {
        if (!config.isUseLlm()) {
            return "为了更准确回答，请补充问题的具体场景，例如涉及哪一年、学院或制度名称。";
        }
        String output = llmChat.generateCompletion(
            DEFAULT_CLARIFY_PROMPT,
            query,
            64,
            CRAG_TEMPERATURE,
            CRAG_TOP_P
        );
        if (output == null || output.isBlank()) {
            return "为了更准确回答，请补充问题的具体场景，例如涉及哪一年、学院或制度名称。";
        }
        return output.trim();
    }

    private boolean isLikelyAmbiguous(String message) {
        if (message == null) {
            return true;
        }
        String trimmed = message.trim();
        RagProperties.Crag cragConfig = ragProperties.getCrag();
        if (trimmed.length() < cragConfig.getAmbiguityMinLength()) {
            return true;
        }
        for (String word : cragConfig.getAmbiguityWords()) {
            if (trimmed.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private String compact(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
