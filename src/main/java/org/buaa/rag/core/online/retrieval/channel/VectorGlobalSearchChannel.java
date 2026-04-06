package org.buaa.rag.core.online.retrieval.channel;

import java.util.List;
import java.util.Map;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.properties.SearchChannelProperties;
import org.buaa.rag.core.online.retrieval.SmartRetrieverService;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局向量检索通道。
 * <p>
 * 当意图置信度不足（或不存在有效意图）时承担兜底召回，
 * 先尝试纯向量检索，无结果则降级为混合检索。
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
    public boolean canActivate(SearchContext context) {
        if (!properties.getChannels().getVectorGlobal().isEnabled()) {
            return false;
        }
        double threshold = properties.getChannels().getVectorGlobal().getConfidenceThreshold();
        if (context.getIntentDecisions() != null && !context.getIntentDecisions().isEmpty()) {
            double peak = context.getIntentDecisions().stream()
                .filter(d -> d != null && d.getAction() == IntentDecision.Action.ROUTE_RAG)
                .map(IntentDecision::getConfidence)
                .filter(c -> c != null)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);
            return peak < threshold;
        }
        IntentDecision single = context.getIntentDecision();
        if (single == null || single.getAction() != IntentDecision.Action.ROUTE_RAG) {
            return true;
        }
        double conf = single.getConfidence() == null ? 0.0 : single.getConfidence();
        return conf < threshold;
    }

    @Override
    public SearchChannelResult execute(SearchContext context) {
        long t0 = System.currentTimeMillis();
        try {
            int multiplier = Math.max(1, properties.getChannels().getVectorGlobal().getTopKMultiplier());
            int effectiveTopK = Math.max(1, context.getTopK() * multiplier);
            List<RetrievalMatch> hits = smartRetrieverService.retrieveVectorOnly(
                context.effectiveQuery(), effectiveTopK, context.getUserId());
            if (hits.isEmpty()) {
                hits = smartRetrieverService.retrieve(context.effectiveQuery(), effectiveTopK, context.getUserId());
            }
            double conf = computeConfidence(context.getIntentDecision());
            return SearchChannelResult.builder()
                .channelType(SearchChannelType.VECTOR_GLOBAL)
                .channelName(getName())
                .matches(hits)
                .confidence(conf)
                .elapsedMs(System.currentTimeMillis() - t0)
                .metadata(Map.of("topK", effectiveTopK))
                .build();
        } catch (Exception ex) {
            log.warn("全局向量检索失败", ex);
            return SearchChannelResult.builder()
                .channelType(SearchChannelType.VECTOR_GLOBAL)
                .channelName(getName())
                .matches(List.of())
                .confidence(0.0)
                .elapsedMs(System.currentTimeMillis() - t0)
                .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR_GLOBAL;
    }

    private double computeConfidence(IntentDecision decision) {
        if (decision == null || decision.getConfidence() == null) {
            return properties.getChannels().getVectorGlobal().getDefaultConfidence();
        }
        return Math.max(0.2, 1.0 - decision.getConfidence());
    }
}
