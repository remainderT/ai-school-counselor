package org.buaa.rag.core.online.retrieval.channel;

import java.util.List;

import org.buaa.rag.common.enums.SearchChannelType;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.retrieval.SmartRetrieverService;
import org.buaa.rag.properties.SearchChannelProperties;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ES/BM25 稀疏文本检索通道。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SparseTextSearchChannel implements SearchChannel {

    private final SmartRetrieverService smartRetrieverService;
    private final SearchChannelProperties properties;

    @Override
    public String channelId() {
        return "sparse-text";
    }

    @Override
    public String description() {
        return "基于 Elasticsearch BM25 的稀疏文本召回通道";
    }

    @Override
    public int dispatchOrder() {
        return 5;
    }

    @Override
    public boolean isApplicable(SearchContext ctx) {
        return properties.getChannels().getSparseText().isEnabled();
    }

    @Override
    public SearchChannelResult fetch(SearchContext context) {
        long start = System.nanoTime();
        try {
            int multiplier = Math.max(1, properties.getChannels().getSparseText().getTopKMultiplier());
            int effectiveTopK = Math.max(1, context.getTopK() * multiplier);
            List<RetrievalMatch> hits = smartRetrieverService.retrieveTextOnly(
                context.resolvedQuery(), effectiveTopK, context.getUserId());
            hits.forEach(hit -> hit.setChannelType(SearchChannelType.SPARSE_TEXT));
            double topScore = hits.stream()
                .mapToDouble(hit -> hit.getRelevanceScore() != null ? hit.getRelevanceScore() : 0.0)
                .max()
                .orElse(0.0);
            return SearchChannelResult.of(
                SearchChannelType.SPARSE_TEXT, channelId(), hits, topScore, nanosToMs(start));
        } catch (Exception ex) {
            log.warn("稀疏文本检索失败", ex);
            return SearchChannelResult.empty(SearchChannelType.SPARSE_TEXT, channelId());
        }
    }

    @Override
    public SearchChannelType channelType() {
        return SearchChannelType.SPARSE_TEXT;
    }

    private long nanosToMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
