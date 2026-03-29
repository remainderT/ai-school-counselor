package org.buaa.rag.core.online.retrieval.channel;

import java.util.List;
import java.util.Map;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.properties.SearchChannelProperties;
import org.buaa.rag.service.SmartRetrieverService;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局向量检索通道
 * 当意图置信度不足时承担兜底召回
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorGlobalSearchChannel implements SearchChannel {

    private final SmartRetrieverService smartRetrieverService;
    private final SearchChannelProperties properties;

    @Override
    public String getName() {
        return "vector-global";
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        if (!properties.getChannels().getVectorGlobal().isEnabled()) {
            return false;
        }
        if (context.getIntentDecisions() != null && !context.getIntentDecisions().isEmpty()) {
            double maxConfidence = context.getIntentDecisions().stream()
                .filter(decision -> decision != null && decision.getAction() == IntentDecision.Action.ROUTE_RAG)
                .map(IntentDecision::getConfidence)
                .filter(confidence -> confidence != null)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);
            return maxConfidence < properties.getChannels().getVectorGlobal().getConfidenceThreshold();
        }
        IntentDecision decision = context.getIntentDecision();
        if (decision == null || decision.getAction() != IntentDecision.Action.ROUTE_RAG) {
            return true;
        }
        double confidence = decision.getConfidence() == null ? 0.0 : decision.getConfidence();
        return confidence < properties.getChannels().getVectorGlobal().getConfidenceThreshold();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long start = System.currentTimeMillis();
        try {
            int multiplier = Math.max(1, properties.getChannels().getVectorGlobal().getTopKMultiplier());
            int topK = Math.max(1, context.getTopK() * multiplier);
            List<RetrievalMatch> matches = smartRetrieverService.retrieveVectorOnly(
                context.getMainQuery(),
                topK,
                context.getUserId()
            );
            if (matches.isEmpty()) {
                matches = smartRetrieverService.retrieve(context.getMainQuery(), topK, context.getUserId());
            }

            double confidence = resolveChannelConfidence(context.getIntentDecision());
            return SearchChannelResult.builder()
                .channelType(SearchChannelType.VECTOR_GLOBAL)
                .channelName(getName())
                .matches(matches)
                .confidence(confidence)
                .latencyMs(System.currentTimeMillis() - start)
                .metadata(Map.of("topK", topK))
                .build();
        } catch (Exception e) {
            log.warn("全局向量检索失败", e);
            return SearchChannelResult.builder()
                .channelType(SearchChannelType.VECTOR_GLOBAL)
                .channelName(getName())
                .matches(List.of())
                .confidence(0.0)
                .latencyMs(System.currentTimeMillis() - start)
                .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR_GLOBAL;
    }

    private double resolveChannelConfidence(IntentDecision decision) {
        if (decision == null || decision.getConfidence() == null) {
            return 0.7;
        }
        return Math.max(0.2, 1.0 - decision.getConfidence());
    }
}
