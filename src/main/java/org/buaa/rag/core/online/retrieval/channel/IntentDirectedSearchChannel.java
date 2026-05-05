package org.buaa.rag.core.online.retrieval.channel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.buaa.rag.common.enums.SearchChannelType;
import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.retrieval.SmartRetrieverService;
import org.buaa.rag.properties.SearchChannelProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 意图定向检索通道。
 * <p>
 * 当存在高置信意图时执行精准召回，支持多意图并行检索后合并去重。
 */
@Slf4j
@Component
public class IntentDirectedSearchChannel implements SearchChannel {

    private final SmartRetrieverService smartRetrieverService;
    private final SearchChannelProperties properties;
    public IntentDirectedSearchChannel(SmartRetrieverService smartRetrieverService,
                                       SearchChannelProperties properties) {
        this.smartRetrieverService = smartRetrieverService;
        this.properties = properties;
    }

    @Override
    public String channelId() {
        return "intent-directed";
    }

    @Override
    public String description() {
        return "基于意图判定的精准定向检索通道";
    }

    @Override
    public int dispatchOrder() {
        return 1;
    }

    @Override
    public boolean isApplicable(SearchContext context) {
        if (!properties.getChannels().getIntentDirected().isEnabled()) {
            return false;
        }
        double minScore = properties.getChannels().getIntentDirected().getMinIntentScore();
        List<IntentDecision> decisions = context.getIntentDecisions();
        if (decisions != null && !decisions.isEmpty()) {
            return decisions.stream().anyMatch(d ->
                d != null
                    && d.getAction() == IntentDecision.Action.ROUTE_RAG
                    && (d.getConfidence() == null || d.getConfidence() >= minScore)
            );
        }
        IntentDecision single = context.getIntentDecision();
        if (single == null || single.getAction() != IntentDecision.Action.ROUTE_RAG) {
            return false;
        }
        double conf = single.getConfidence() == null ? 0.0 : single.getConfidence();
        return conf >= minScore;
    }

    @Override
    public SearchChannelResult fetch(SearchContext context) {
        long t0 = System.nanoTime();
        try {
            int multiplier = Math.max(1, properties.getChannels().getIntentDirected().getTopKMultiplier());
            int effectiveTopK = Math.max(1, context.getTopK() * multiplier);
            List<IntentDecision> decisions = context.getIntentDecisions();
            List<RetrievalMatch> hits;
            if (decisions != null && !decisions.isEmpty()) {
                hits = fetchMultiIntent(context, decisions, effectiveTopK);
            } else {
                hits = fetchSingleIntent(context, context.getIntentDecision(), effectiveTopK, context.resolvedQuery());
            }

            // 为命中结果标记来源通道
            hits.forEach(h -> h.setChannelType(SearchChannelType.INTENT_DIRECTED));

            double topScore = hits.stream()
                    .mapToDouble(h -> h.getRelevanceScore() != null ? h.getRelevanceScore() : 0.0)
                    .max().orElse(0.0);

            return SearchChannelResult.of(
                    SearchChannelType.INTENT_DIRECTED, channelId(),
                    hits, topScore, nanosToMs(t0));
        } catch (Exception ex) {
            log.warn("意图定向检索失败", ex);
            return SearchChannelResult.empty(SearchChannelType.INTENT_DIRECTED, channelId());
        }
    }

    @Override
    public SearchChannelType channelType() {
        return SearchChannelType.INTENT_DIRECTED;
    }

    private long nanosToMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    // ----- 内部方法 -----

    private String enrichQuery(String query, IntentDecision decision) {
        String base = StringUtils.hasText(query) ? query.trim() : "";
        if (decision == null) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base);
        appendIfNotPresent(sb, base, decision.getLevel2());
        appendIfNotPresent(sb, base, decision.getLevel1());
        return sb.toString().trim();
    }

    private void appendIfNotPresent(StringBuilder sb, String base, String extra) {
        if (!StringUtils.hasText(extra)) {
            return;
        }
        String trimmed = extra.trim();
        if (base.contains(trimmed)) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(trimmed);
    }

    private List<RetrievalMatch> fetchMultiIntent(SearchContext context,
                                                   List<IntentDecision> decisions,
                                                   int topK) {
        double minScore = properties.getChannels().getIntentDirected().getMinIntentScore();
        List<IntentDecision> qualified = decisions.stream()
            .filter(d -> d != null && d.getAction() == IntentDecision.Action.ROUTE_RAG)
            .filter(d -> d.getConfidence() == null || d.getConfidence() >= minScore)
            .limit(4)
            .toList();
        if (qualified.isEmpty()) {
            return List.of();
        }

        Map<String, RetrievalMatch> dedup = qualified.stream()
            .map(d -> fetchSingleIntent(context, d, topK, context.resolvedQuery()))
            .flatMap(List::stream)
            .collect(Collectors.toMap(
                RetrievalMatch::matchKey,
                m -> m,
                (a, b) -> relevance(a) >= relevance(b) ? a : b
            ));
        List<RetrievalMatch> sorted = dedup.values().stream()
            .sorted((a, b) -> Double.compare(relevance(b), relevance(a)))
            .toList();
        return sorted.size() > topK ? sorted.subList(0, topK) : sorted;
    }

    private List<RetrievalMatch> fetchSingleIntent(SearchContext context,
                                                    IntentDecision decision,
                                                    int topK,
                                                    String baseQuery) {
        String query = enrichQuery(baseQuery, decision);
        Set<Long> kbIds = decision.getKnowledgeBaseId() != null
            ? Set.of(decision.getKnowledgeBaseId())
            : Set.of();
        if (!kbIds.isEmpty()) {
            return smartRetrieverService.retrieveScoped(query, topK, context.getUserId(), kbIds);
        }
        return smartRetrieverService.retrieve(query, topK, context.getUserId());
    }


    private double relevance(RetrievalMatch m) {
        return m == null || m.getRelevanceScore() == null ? 0.0 : m.getRelevanceScore();
    }
}
