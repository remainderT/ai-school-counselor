package org.buaa.rag.module.retrieval.channel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import org.buaa.rag.dto.IntentDecision;
import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.properties.SearchChannelProperties;
import org.buaa.rag.service.SmartRetrieverService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 意图定向检索通道
 * 基于高置信意图执行精准召回
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentDirectedSearchChannel implements SearchChannel {

    private final SmartRetrieverService smartRetrieverService;
    private final SearchChannelProperties properties;
    @Qualifier("retrievalChannelExecutor")
    private final Executor retrievalChannelExecutor;

    @Override
    public String getName() {
        return "intent-directed";
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        if (!properties.getChannels().getIntentDirected().isEnabled()) {
            return false;
        }
        List<IntentDecision> decisions = context.getIntentDecisions();
        if (decisions != null && !decisions.isEmpty()) {
            double minScore = properties.getChannels().getIntentDirected().getMinIntentScore();
            return decisions.stream().anyMatch(decision ->
                decision != null
                    && decision.getAction() == IntentDecision.Action.ROUTE_RAG
                    && (decision.getConfidence() == null || decision.getConfidence() >= minScore)
            );
        }
        IntentDecision decision = context.getIntentDecision();
        if (decision == null || decision.getAction() != IntentDecision.Action.ROUTE_RAG) {
            return false;
        }
        double confidence = decision.getConfidence() == null ? 0.0 : decision.getConfidence();
        return confidence >= properties.getChannels().getIntentDirected().getMinIntentScore();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long start = System.currentTimeMillis();
        try {
            int multiplier = Math.max(1, properties.getChannels().getIntentDirected().getTopKMultiplier());
            int topK = Math.max(1, context.getTopK() * multiplier);
            List<IntentDecision> decisions = context.getIntentDecisions();
            List<RetrievalMatch> matches;
            String query;
            if (decisions != null && !decisions.isEmpty()) {
                matches = searchMultiIntent(context, decisions, topK);
                query = context.getMainQuery();
            } else {
                query = buildDirectedQuery(context.getMainQuery(), context.getIntentDecision());
                matches = smartRetrieverService.retrieve(query, topK, context.getUserId());
            }

            double confidence = context.getIntentDecision() != null && context.getIntentDecision().getConfidence() != null
                ? context.getIntentDecision().getConfidence()
                : 0.0;

            return SearchChannelResult.builder()
                .channelType(SearchChannelType.INTENT_DIRECTED)
                .channelName(getName())
                .matches(matches)
                .confidence(confidence)
                .latencyMs(System.currentTimeMillis() - start)
                .metadata(Map.of("query", query, "topK", topK, "intentCount", decisions == null ? 0 : decisions.size()))
                .build();
        } catch (Exception e) {
            log.warn("意图定向检索失败", e);
            return SearchChannelResult.builder()
                .channelType(SearchChannelType.INTENT_DIRECTED)
                .channelName(getName())
                .matches(List.of())
                .confidence(0.0)
                .latencyMs(System.currentTimeMillis() - start)
                .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.INTENT_DIRECTED;
    }

    private String buildDirectedQuery(String query, IntentDecision decision) {
        String base = StringUtils.hasText(query) ? query.trim() : "";
        if (decision == null) {
            return base;
        }

        StringBuilder builder = new StringBuilder(base);
        appendIfAbsent(builder, base, decision.getLevel2());
        appendIfAbsent(builder, base, decision.getLevel1());
        return builder.toString().trim();
    }

    private void appendIfAbsent(StringBuilder builder, String base, String extra) {
        if (!StringUtils.hasText(extra)) {
            return;
        }
        String normalized = extra.trim();
        if (base.contains(normalized)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(normalized);
    }

    private List<RetrievalMatch> searchMultiIntent(SearchContext context,
                                                   List<IntentDecision> decisions,
                                                   int topK) {
        double minScore = properties.getChannels().getIntentDirected().getMinIntentScore();
        List<IntentDecision> ragDecisions = decisions.stream()
            .filter(decision -> decision != null && decision.getAction() == IntentDecision.Action.ROUTE_RAG)
            .filter(decision -> decision.getConfidence() == null || decision.getConfidence() >= minScore)
            .limit(4)
            .toList();
        if (ragDecisions.isEmpty()) {
            return List.of();
        }

        List<CompletableFuture<List<RetrievalMatch>>> futures = ragDecisions.stream()
            .map(decision -> CompletableFuture.supplyAsync(
                () -> searchSingleIntent(context, decision, topK),
                retrievalChannelExecutor
            ))
            .toList();

        Map<String, RetrievalMatch> dedup = futures.stream()
            .map(CompletableFuture::join)
            .flatMap(List::stream)
            .collect(Collectors.toMap(
                this::keyOf,
                match -> match,
                (left, right) -> scoreOf(left) >= scoreOf(right) ? left : right
            ));
        List<RetrievalMatch> merged = dedup.values().stream()
            .sorted((a, b) -> Double.compare(scoreOf(b), scoreOf(a)))
            .toList();
        if (merged.size() > topK) {
            return merged.subList(0, topK);
        }
        return merged;
    }

    private List<RetrievalMatch> searchSingleIntent(SearchContext context,
                                                    IntentDecision decision,
                                                    int topK) {
        String query = buildDirectedQuery(context.getMainQuery(), decision);
        Set<Long> knowledgeIds = parseKnowledgeIds(decision.getKnowledgeBaseId());
        if (!knowledgeIds.isEmpty()) {
            return smartRetrieverService.retrieveScoped(query, topK, context.getUserId(), knowledgeIds);
        }
        return smartRetrieverService.retrieve(query, topK, context.getUserId());
    }

    private Set<Long> parseKnowledgeIds(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        return List.of(text.split(",")).stream()
            .map(String::trim)
            .filter(StringUtils::hasText)
            .map(value -> {
                try {
                    return Long.valueOf(value);
                } catch (Exception ignore) {
                    return null;
                }
            })
            .filter(id -> id != null)
            .collect(Collectors.toSet());
    }

    private String keyOf(RetrievalMatch match) {
        String md5 = match == null || match.getFileMd5() == null ? "unknown" : match.getFileMd5();
        String chunk = match == null || match.getChunkId() == null ? "0" : String.valueOf(match.getChunkId());
        return md5 + ":" + chunk;
    }

    private double scoreOf(RetrievalMatch match) {
        if (match == null || match.getRelevanceScore() == null) {
            return 0.0;
        }
        return match.getRelevanceScore();
    }
}
