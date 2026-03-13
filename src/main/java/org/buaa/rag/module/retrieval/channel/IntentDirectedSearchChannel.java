package org.buaa.rag.module.retrieval.channel;

import java.util.List;
import java.util.Map;

import org.buaa.rag.dto.IntentDecision;
import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.properties.SearchChannelProperties;
import org.buaa.rag.service.SmartRetrieverService;
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
            String query = buildDirectedQuery(context.getMainQuery(), context.getIntentDecision());
            List<RetrievalMatch> matches = smartRetrieverService.retrieve(query, topK, context.getUserId());

            double confidence = context.getIntentDecision() != null && context.getIntentDecision().getConfidence() != null
                ? context.getIntentDecision().getConfidence()
                : 0.0;

            return SearchChannelResult.builder()
                .channelType(SearchChannelType.INTENT_DIRECTED)
                .channelName(getName())
                .matches(matches)
                .confidence(confidence)
                .latencyMs(System.currentTimeMillis() - start)
                .metadata(Map.of("query", query, "topK", topK))
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
}
