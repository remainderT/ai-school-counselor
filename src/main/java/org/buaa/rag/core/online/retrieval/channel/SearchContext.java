package org.buaa.rag.core.online.retrieval.channel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.core.model.IntentDecision;

import lombok.Builder;
import lombok.Data;

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

    public String getMainQuery() {
        if (rewrittenQuery != null && !rewrittenQuery.isBlank()) {
            return rewrittenQuery;
        }
        return originalQuery;
    }
}
