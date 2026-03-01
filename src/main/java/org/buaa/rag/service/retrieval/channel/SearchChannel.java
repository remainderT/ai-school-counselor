package org.buaa.rag.service.retrieval.channel;

public interface SearchChannel {

    String getName();

    int getPriority();

    boolean isEnabled(SearchContext context);

    SearchChannelResult search(SearchContext context);

    SearchChannelType getType();
}
