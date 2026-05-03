package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelResult;
import org.buaa.rag.core.online.retrieval.channel.SearchContext;
import org.springframework.stereotype.Component;

/**
 * 最终截断处理器：确保返回的结果数量不超过请求的 topK。
 *
 * <p>始终激活，位于后处理链末尾（stage=100），作为安全兜底。
 */
@Component
public class TopKLimitPostProcessor implements SearchResultPostProcessor {

    @Override
    public String label() {
        return "topk-truncation";
    }

    @Override
    public int stage() {
        return 100;
    }

    @Override
    public List<RetrievalMatch> process(List<RetrievalMatch> candidates,
                                        List<SearchChannelResult> channelOutputs,
                                        SearchContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, ctx.getTopK());
        return candidates.size() <= limit ? candidates : List.copyOf(candidates.subList(0, limit));
    }
}
