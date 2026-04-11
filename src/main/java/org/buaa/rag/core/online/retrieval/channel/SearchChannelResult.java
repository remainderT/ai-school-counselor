package org.buaa.rag.core.online.retrieval.channel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.common.enums.SearchChannelType;
import org.buaa.rag.core.model.RetrievalMatch;

import lombok.Builder;
import lombok.Data;

/**
 * 单个检索通道的执行结果。
 */
@Data
@Builder
public class SearchChannelResult {

    private SearchChannelType channelType;
    private String channelName;
    private List<RetrievalMatch> matches;
    private double confidence;
    private long elapsedMs;

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
