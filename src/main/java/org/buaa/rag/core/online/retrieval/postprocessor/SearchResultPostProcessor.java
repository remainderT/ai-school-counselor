package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelResult;
import org.buaa.rag.core.online.retrieval.channel.SearchContext;

public interface SearchResultPostProcessor {

    String getName();

    int getOrder();

    boolean isEnabled(SearchContext context);

    List<RetrievalMatch> process(List<RetrievalMatch> matches,
                                 List<SearchChannelResult> results,
                                 SearchContext context);
}
