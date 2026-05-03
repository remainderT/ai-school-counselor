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
 * 精排后处理器。
 *
 * <p>将多通道合并后的候选集提交给 {@link RoutingRerankService}
 * 做语义精排，并截取 topK 条最终结果。精排服务内部支持三级降级
 * （DashScope → LLM Prompt → 直接截断），本处理器无需关心降级细节。
 *
 * <p>当候选数不超过 topK 时自动跳过精排，避免无意义的 API 调用开销。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankPostProcessor implements SearchResultPostProcessor {

    /** 少于此数量的候选不值得调用精排模型 */
    private static final int MIN_CANDIDATES_FOR_RERANK = 2;

    private final SearchChannelProperties properties;
    private final RoutingRerankService rerankService;

    @Override
    public String label() {
        return "semantic-rerank";
    }

    @Override
    public int stage() {
        return 20;
    }

    @Override
    public boolean isActive(SearchContext ctx) {
        return properties.getPostProcessor().isRerank();
    }

    @Override
    public List<RetrievalMatch> process(List<RetrievalMatch> candidates,
                                        List<SearchChannelResult> channelOutputs,
                                        SearchContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        // 候选数量太少或已不超过 topK，精排无收益
        if (candidates.size() < MIN_CANDIDATES_FOR_RERANK
                || candidates.size() <= ctx.getTopK()) {
            log.debug("精排跳过: 候选数({})不超过 topK({})或低于最低阈值",
                    candidates.size(), ctx.getTopK());
            return candidates;
        }
        return rerankService.rerank(
                ctx.resolvedQuery(),
                new ArrayList<>(candidates),
                ctx.getTopK()
        );
    }
}
