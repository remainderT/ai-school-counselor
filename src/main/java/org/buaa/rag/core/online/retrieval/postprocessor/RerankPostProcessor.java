package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.ArrayList;
import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.rerank.RoutingRerankService;
import org.buaa.rag.properties.SearchChannelProperties;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelResult;
import org.buaa.rag.core.online.retrieval.channel.SearchContext;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 精排后处理器：使用 {@link RerankService}（支持独立 Rerank 模型 + LLM 降级）
 * 对多通道合并结果做二次排序。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankPostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties properties;
    private final RoutingRerankService rerankService;

    @Override
    public String getName() {
        return "rerank";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public boolean shouldApply(SearchContext context) {
        return properties.getPostProcessor().isRerank();
    }

    @Override
    public List<RetrievalMatch> apply(List<RetrievalMatch> matches,
                                      List<SearchChannelResult> channelResults,
                                      SearchContext context) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        return rerankService.rerank(
                context.effectiveQuery(),
                new ArrayList<>(matches),
                context.getTopK()
        );
    }
}
