package org.buaa.rag.module.retrieval.channel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.dto.RetrievalMatch;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchChannelResult {

    private SearchChannelType channelType;
    private String channelName;
    private List<RetrievalMatch> matches;
    private double confidence;
    private long latencyMs;

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
