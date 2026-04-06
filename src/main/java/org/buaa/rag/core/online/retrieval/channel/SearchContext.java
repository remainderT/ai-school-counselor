package org.buaa.rag.core.online.retrieval.channel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.core.model.IntentDecision;

import lombok.Builder;
import lombok.Data;

/**
 * 封装一次多通道检索所需的全部输入参数。
 */
@Data
@Builder
public class SearchContext {

    private String userId;
    private String originalQuery;
    private String rewrittenQuery;
    private int topK;
    private IntentDecision intentDecision;

    @Builder.Default
    private List<IntentDecision> intentDecisions = List.of();

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 返回经过改写的查询文本；若改写为空则退化为原始查询。
     */
    public String effectiveQuery() {
        if (rewrittenQuery != null && !rewrittenQuery.isBlank()) {
            return rewrittenQuery;
        }
        return originalQuery;
    }
}
