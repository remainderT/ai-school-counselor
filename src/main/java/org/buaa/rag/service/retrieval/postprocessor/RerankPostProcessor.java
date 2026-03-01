package org.buaa.rag.service.retrieval.postprocessor;

import java.util.ArrayList;
import java.util.List;

import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.properties.SearchChannelProperties;
import org.buaa.rag.service.RetrievalPostProcessorService;
import org.buaa.rag.service.retrieval.channel.SearchChannelResult;
import org.buaa.rag.service.retrieval.channel.SearchContext;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RerankPostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties properties;
    private final RetrievalPostProcessorService retrievalPostProcessorService;

    @Override
    public String getName() {
        return "rerank";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getPostProcessor().isRerank();
    }

    @Override
    public List<RetrievalMatch> process(List<RetrievalMatch> matches,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        return retrievalPostProcessorService.rerank(
            context.getMainQuery(),
            new ArrayList<>(matches),
            context.getTopK()
        );
    }
}
