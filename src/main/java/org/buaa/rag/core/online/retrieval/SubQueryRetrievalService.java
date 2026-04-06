package org.buaa.rag.core.online.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.core.model.CragDecision;
import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.intent.IntentResolutionService;
import org.buaa.rag.core.online.intent.SubQueryIntent;
import org.buaa.rag.core.online.retrieval.postprocessor.RetrievalPostProcessorService;
import org.buaa.rag.core.online.rewrite.QueryRewriteAndSplitService;
import org.buaa.rag.core.online.rewrite.QueryRewriteResult;
import org.buaa.rag.properties.RagProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 子问题检索服务：封装单个子问题的完整检索流程。
 *
 * <p>职责：策略选择 → 多通道检索 → 去重 → Fallback → RRF 融合 → CRAG 评估。
 * 可被单独测试，也可在子问题粒度并行时被多个 {@code CompletableFuture} 并行调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubQueryRetrievalService {

    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final SmartRetrieverService smartRetrieverService;
    private final RetrievalPostProcessorService postProcessorService;
    private final IntentResolutionService intentResolutionService;
    private final QueryRewriteAndSplitService queryRewriteAndSplitService;
    private final RagProperties ragProperties;

    /**
     * 单个子问题的完整检索流程：topK计算 → 检索 → CRAG评估 → fallback。
     * 使用子问题已解析的候选意图，避免二次LLM调用。
     */
    public SubQueryRetrievalResult retrieveForSubQuery(String userId, SubQueryIntent subQueryIntent) {
        String query = subQueryIntent.subQuery();
        IntentDecision primaryIntent = selectPrimaryIntent(subQueryIntent);
        List<IntentDecision> preResolvedCandidates = subQueryIntent.candidates() != null
                ? subQueryIntent.candidates()
                : List.of();
        int topK = determineTopK(query);
        List<RetrievalMatch> results = retrieveByStrategy(userId, query, topK, primaryIntent, preResolvedCandidates);
        CragDecision decision = postProcessorService.evaluate(query, results);

        boolean clarifyTriggered = false;
        String clarifyMessage = null;
        if (decision.getAction() == CragDecision.Action.CLARIFY) {
            clarifyTriggered = true;
            clarifyMessage = decision.getMessage();
        } else if (decision.getAction() == CragDecision.Action.REFINE) {
            List<RetrievalMatch> fallback = fallbackRetrieval(userId, query, topK);
            if (!fallback.isEmpty()) {
                results = fallback;
            }
        } else if (decision.getAction() == CragDecision.Action.NO_ANSWER) {
            results = List.of();
        }

        List<RetrievalMatch> safeResults = results != null ? results : List.of();
        return new SubQueryRetrievalResult(
                query,
                primaryIntent,
                safeResults,
                clarifyTriggered,
                clarifyMessage,
                null,
                safeResults.size(),
                topK
        );
    }

    /**
     * 根据意图策略检索，返回 Rerank 后的结果。
     * 使用预解析的候选意图，避免再次调用 intentResolutionService。
     *
     * @param userId                用户 ID
     * @param query                 子问题文本
     * @param topK                  目标返回数量
     * @param intent                路由意图（null 时退化为 HYBRID 全局检索）
     * @param preResolvedCandidates 上层已解析的意图候选列表，非空时跳过内部重新解析
     */
    public List<RetrievalMatch> retrieveByStrategy(String userId,
                                                   String query,
                                                   int topK,
                                                   IntentDecision intent,
                                                   List<IntentDecision> preResolvedCandidates) {
        IntentDecision resolved = intent != null ? intent : defaultHybridIntent();
        IntentDecision.Strategy strategy = resolved.getStrategy() != null
                ? resolved.getStrategy()
                : IntentDecision.Strategy.HYBRID;

        if (strategy == IntentDecision.Strategy.PRECISION) {
            List<RetrievalMatch> results = smartRetrieverService.retrieveTextOnly(query, topK, userId);
            return postProcessorService.rerank(query, results, topK);
        }

        if (strategy == IntentDecision.Strategy.CLARIFY_ONLY) {
            return List.of();
        }

        // HYBRID：多通道检索 + RRF 融合
        IntentDecision retrievalIntent = (resolved.getAction() == IntentDecision.Action.ROUTE_RAG)
                ? resolved : null;
        List<IntentDecision> candidates = (preResolvedCandidates != null && !preResolvedCandidates.isEmpty())
                ? preResolvedCandidates
                : intentResolutionService.resolveForQuery(userId, query);
        return retrieveWithFusion(userId, query, topK, retrievalIntent, candidates);
    }

    /**
     * 根据意图策略检索（无预解析候选，内部重新解析）。
     */
    public List<RetrievalMatch> retrieveByStrategy(String userId,
                                                   String query,
                                                   int topK,
                                                   IntentDecision intent) {
        return retrieveByStrategy(userId, query, topK, intent, null);
    }

    /**
     * 计算适合此子问题的 topK：根据问题长度和多意图暗示动态调整。
     */
    public int determineTopK(String query) {
        RagProperties.Retrieval cfg = ragProperties.getRetrieval();
        int defaultK = cfg.getDefaultTopK();
        int maxK = cfg.getMaxTopK();
        if (!StringUtils.hasText(query)) {
            return defaultK;
        }
        int k = defaultK;
        int len = query.trim().length();
        if (len > 40) {
            k += 3;
        } else if (len > 20) {
            k += 1;
        }
        if (hasMultiIntentHint(query)) {
            k += 2;
        }
        return Math.min(k, maxK);
    }

    /**
     * 对多个子问题的检索结果做全局去重（保留最高分），并按得分降序排列。
     */
    public List<RetrievalMatch> deduplicateAndSort(List<RetrievalMatch> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        Map<String, RetrievalMatch> best = new HashMap<>();
        for (RetrievalMatch m : sources) {
            if (m == null) {
                continue;
            }
            String key = m.matchKey();
            best.merge(key, m, (existing, incoming) -> {
                double es = existing.getRelevanceScore() != null ? existing.getRelevanceScore() : 0.0;
                double is = incoming.getRelevanceScore() != null ? incoming.getRelevanceScore() : 0.0;
                return es >= is ? existing : incoming;
            });
        }
        List<RetrievalMatch> result = new ArrayList<>(best.values());
        result.sort((a, b) -> Double.compare(
                b.getRelevanceScore() != null ? b.getRelevanceScore() : 0.0,
                a.getRelevanceScore() != null ? a.getRelevanceScore() : 0.0
        ));
        return result;
    }

    /**
     * Fallback 检索：当主检索质量不足时，用文本精确检索兜底。
     */
    public List<RetrievalMatch> fallbackRetrieval(String userId, String query, int topK) {
        RagProperties.Crag cfg = ragProperties.getCrag();
        int multiplier = (cfg != null) ? cfg.getFallbackMultiplier() : 2;
        int fallbackK = Math.min(topK * Math.max(1, multiplier), ragProperties.getRetrieval().getMaxTopK());
        List<RetrievalMatch> fallback = smartRetrieverService.retrieveTextOnly(query, fallbackK, userId);
        return postProcessorService.rerank(query, fallback, topK);
    }

    public IntentDecision selectPrimaryIntent(SubQueryIntent subQueryIntent) {
        if (subQueryIntent != null
                && subQueryIntent.candidates() != null
                && !subQueryIntent.candidates().isEmpty()) {
            return subQueryIntent.candidates().get(0);
        }
        return defaultHybridIntent();
    }

    public IntentDecision defaultHybridIntent() {
        return IntentDecision.builder()
                .action(IntentDecision.Action.ROUTE_RAG)
                .strategy(IntentDecision.Strategy.HYBRID)
                .build();
    }

    // ──────────────────────── private ────────────────────────

    /**
     * 多检索路径 + RRF 融合：
     * 1. 主路径（意图定向/全局向量）
     * 2. HyDE 扩展路径（配置启用时）
     */
    private List<RetrievalMatch> retrieveWithFusion(String userId,
                                                    String query,
                                                    int topK,
                                                    IntentDecision intent,
                                                    List<IntentDecision> intentCandidates) {
        List<List<RetrievalMatch>> resultSets = new ArrayList<>();
        resultSets.add(retrieveWithFallbackIfNeeded(userId, query, topK, intent, intentCandidates));

        if (ragProperties.getHyde().isEnabled()) {
            QueryRewriteResult hydeResult = queryRewriteAndSplitService.rewriteAndSplit(query);
            String hydeQuery = hydeResult.rewrittenQuery();
            if (StringUtils.hasText(hydeQuery) && !hydeQuery.equals(query)) {
                resultSets.add(multiChannelRetrievalEngine.retrieve(userId, hydeQuery, topK, null));
            }
        }

        if (!ragProperties.getFusion().isEnabled() || resultSets.size() == 1) {
            // 单路径已在 MultiChannelRetrievalEngine 的 RerankPostProcessor 中完成 rerank，无需重复
            return resultSets.get(0);
        }
        // 多路径 RRF 融合后需重新 rerank（融合改变了排序）
        return postProcessorService.rerank(query, fuseByRrf(resultSets, topK), topK);
    }

    private List<RetrievalMatch> retrieveWithFallbackIfNeeded(String userId,
                                                              String query,
                                                              int topK,
                                                              IntentDecision intent,
                                                              List<IntentDecision> intentCandidates) {
        List<RetrievalMatch> results = multiChannelRetrievalEngine.retrieve(
                userId, query, topK, intent, intentCandidates);
        if (!isLowQuality(results)) {
            return results;
        }
        // 规范化查询后重试一次
        String normalized = normalizeQuery(query);
        if (!normalized.equals(query)) {
            List<RetrievalMatch> retry = multiChannelRetrievalEngine.retrieve(
                    userId, normalized, Math.min(topK * 2, ragProperties.getRetrieval().getMaxTopK()), intent, intentCandidates);
            if (!isLowQuality(retry)) {
                return retry;
            }
        }
        return results;
    }

    /** Reciprocal Rank Fusion */
    private List<RetrievalMatch> fuseByRrf(List<List<RetrievalMatch>> resultSets, int topK) {
        int rrfK = ragProperties.getFusion().getRrfK();
        Map<String, RetrievalMatch> bestMatch = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();

        for (List<RetrievalMatch> set : resultSets) {
            if (set == null) {
                continue;
            }
            for (int i = 0; i < set.size(); i++) {
                RetrievalMatch m = set.get(i);
                if (m == null) {
                    continue;
                }
                String key = m.matchKey();
                scores.merge(key, 1.0 / (rrfK + i + 1), Double::sum);
                bestMatch.putIfAbsent(key, m);
            }
        }

        List<RetrievalMatch> fused = new ArrayList<>(bestMatch.size());
        for (Map.Entry<String, RetrievalMatch> entry : bestMatch.entrySet()) {
            entry.getValue().setRelevanceScore(scores.get(entry.getKey()));
            fused.add(entry.getValue());
        }
        fused.sort((a, b) -> Double.compare(
                b.getRelevanceScore() != null ? b.getRelevanceScore() : 0.0,
                a.getRelevanceScore() != null ? a.getRelevanceScore() : 0.0
        ));
        return fused.size() > topK ? fused.subList(0, topK) : fused;
    }

    private boolean isLowQuality(List<RetrievalMatch> results) {
        if (results == null || results.isEmpty()) {
            return true;
        }
        Double top = results.get(0).getRelevanceScore();
        return top == null || top < ragProperties.getRetrieval().getMinAcceptableScore();
    }

    private String normalizeQuery(String query) {
        return query.replaceAll("[\\t\\n\\r]", " ")
                .replaceAll("[，。！？；、]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean hasMultiIntentHint(String query) {
        return query.contains("以及") || query.contains("和") || query.contains("、");
    }

}
