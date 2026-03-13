package org.buaa.rag.module.retrieval.postprocessor;

import java.util.List;

import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.module.retrieval.channel.SearchChannelResult;
import org.buaa.rag.module.retrieval.channel.SearchContext;
import org.springframework.stereotype.Component;

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
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    public List<RetrievalMatch> process(List<RetrievalMatch> matches,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        int topK = Math.max(1, context.getTopK());
        if (matches.size() <= topK) {
            return matches;
        }
        return matches.subList(0, topK);
    }
}
