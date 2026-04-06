package org.buaa.rag.core.online.retrieval.channel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.properties.SearchChannelProperties;
import org.buaa.rag.core.online.retrieval.SmartRetrieverService;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final Executor retrievalChannelExecutor;

    public IntentDirectedSearchChannel(SmartRetrieverService smartRetrieverService,
                                       SearchChannelProperties properties,
                                       @Qualifier("retrievalChannelExecutor") Executor retrievalChannelExecutor) {
        this.smartRetrieverService = smartRetrieverService;
        this.properties = properties;
        this.retrievalChannelExecutor = retrievalChannelExecutor;
    }

    @Override
    public String getName() {
        return "intent-directed";
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public boolean canActivate(SearchContext context) {
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
    public SearchChannelResult execute(SearchContext context) {
        long t0 = System.currentTimeMillis();
        try {
            int multiplier = Math.max(1, properties.getChannels().getIntentDirected().getTopKMultiplier());
            int effectiveTopK = Math.max(1, context.getTopK() * multiplier);
            List<IntentDecision> decisions = context.getIntentDecisions();
            List<RetrievalMatch> hits;
            String queryUsed;
            if (decisions != null && !decisions.isEmpty()) {
                hits = fetchMultiIntent(context, decisions, effectiveTopK);
                queryUsed = context.effectiveQuery();
            } else {
                queryUsed = enrichQuery(context.effectiveQuery(), context.getIntentDecision());
                hits = smartRetrieverService.retrieve(queryUsed, effectiveTopK, context.getUserId());
            }

            double conf = context.getIntentDecision() != null && context.getIntentDecision().getConfidence() != null
                ? context.getIntentDecision().getConfidence()
                : 0.0;

            return SearchChannelResult.builder()
                .channelType(SearchChannelType.INTENT_DIRECTED)
                .channelName(getName())
                .matches(hits)
                .confidence(conf)
                .elapsedMs(System.currentTimeMillis() - t0)
                .metadata(Map.of("query", queryUsed, "topK", effectiveTopK,
                                 "intentCount", decisions == null ? 0 : decisions.size()))
                .build();
        } catch (Exception ex) {
            log.warn("意图定向检索失败", ex);
            return SearchChannelResult.builder()
                .channelType(SearchChannelType.INTENT_DIRECTED)
                .channelName(getName())
                .matches(List.of())
                .confidence(0.0)
                .elapsedMs(System.currentTimeMillis() - t0)
                .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.INTENT_DIRECTED;
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

        List<CompletableFuture<List<RetrievalMatch>>> tasks = qualified.stream()
            .map(d -> CompletableFuture.supplyAsync(
                () -> fetchSingleIntent(context, d, topK), retrievalChannelExecutor))
            .toList();

        Map<String, RetrievalMatch> dedup = tasks.stream()
            .map(CompletableFuture::join)
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
                                                    int topK) {
        String query = enrichQuery(context.effectiveQuery(), decision);
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
