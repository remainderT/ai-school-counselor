package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelResult;
import org.buaa.rag.core.online.retrieval.channel.SearchContext;
import org.springframework.stereotype.Component;

/**
 * 最终截断处理器：保证结果数不超过请求的 topK。
 */
@Component
public class TopKLimitPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "topk-limit";
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public boolean shouldApply(SearchContext context) {
        return true;
    }

    @Override
    public List<RetrievalMatch> apply(List<RetrievalMatch> matches,
                                      List<SearchChannelResult> channelResults,
                                      SearchContext context) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, context.getTopK());
        return matches.size() <= limit ? matches : matches.subList(0, limit);
    }
}
