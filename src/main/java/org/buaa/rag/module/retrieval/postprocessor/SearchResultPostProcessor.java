package org.buaa.rag.module.retrieval.postprocessor;

import java.util.List;

import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.module.retrieval.channel.SearchChannelResult;
import org.buaa.rag.module.retrieval.channel.SearchContext;

public interface SearchResultPostProcessor {

    String getName();

    int getOrder();

    boolean isEnabled(SearchContext context);

    List<RetrievalMatch> process(List<RetrievalMatch> matches,
                                 List<SearchChannelResult> results,
                                 SearchContext context);
}
