package org.buaa.rag.core.online.retrieval.channel;

import java.util.List;
import java.util.Map;

import org.buaa.rag.common.enums.SearchChannelType;
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
    public String channelId() {
        return "vector-global";
    }

    @Override
    public String description() {
        return "全局向量兜底检索通道（低置信或无意图时激活）";
    }

    @Override
    public int dispatchOrder() {
        return 10;
    }

    @Override
    public boolean isApplicable(SearchContext context) {
        if (!properties.getChannels().getVectorGlobal().isEnabled()) {
            return false;
        }
        if (properties.getChannels().getVectorGlobal().isSupplementHighConfidenceIntent()) {
            return true;
        }
        double threshold = properties.getChannels().getVectorGlobal().getConfidenceThreshold();
        if (context.getIntentDecisions() != null && !context.getIntentDecisions().isEmpty()) {
            List<Double> routeScores = context.getIntentDecisions().stream()
                .filter(d -> d != null && d.getAction() == IntentDecision.Action.ROUTE_RAG)
                .map(IntentDecision::getConfidence)
                .filter(c -> c != null)
                .toList();
            double peak = routeScores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            if (routeScores.size() == 1
                && peak < properties.getChannels().getVectorGlobal().getSingleIntentSupplementThreshold()) {
                return true;
            }
            return peak < threshold;
        }
        IntentDecision single = context.getIntentDecision();
        if (single == null || single.getAction() != IntentDecision.Action.ROUTE_RAG) {
            return true;
        }
        double conf = single.getConfidence() == null ? 0.0 : single.getConfidence();
        return conf < threshold
            || conf < properties.getChannels().getVectorGlobal().getSingleIntentSupplementThreshold();
    }

    @Override
    public SearchChannelResult fetch(SearchContext context) {
        long t0 = System.nanoTime();
        try {
            int multiplier = Math.max(1, properties.getChannels().getVectorGlobal().getTopKMultiplier());
            int effectiveTopK = Math.max(1, context.getTopK() * multiplier);

            // 全局兜底通道优先使用混合检索。这里保留“全局”语义，但不只依赖向量：
            // 向量对语义泛化友好，BM25 对人名、数字、简称、发票抬头等精确字段更稳。
            List<RetrievalMatch> hits = smartRetrieverService.retrieve(
                    context.resolvedQuery(), effectiveTopK, context.getUserId());

            // 标记来源通道
            hits.forEach(h -> h.setChannelType(SearchChannelType.VECTOR_GLOBAL));

            double topScore = hits.stream()
                    .mapToDouble(h -> h.getRelevanceScore() != null ? h.getRelevanceScore() : 0.0)
                    .max().orElse(0.0);

            return SearchChannelResult.of(
                    SearchChannelType.VECTOR_GLOBAL, channelId(),
                    hits, topScore, nanosToMs(t0));
        } catch (Exception ex) {
            log.warn("全局向量检索失败", ex);
            return SearchChannelResult.empty(SearchChannelType.VECTOR_GLOBAL, channelId());
        }
    }

    @Override
    public SearchChannelType channelType() {
        return SearchChannelType.VECTOR_GLOBAL;
    }

    private long nanosToMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }


}
