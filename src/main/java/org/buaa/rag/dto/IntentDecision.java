package org.buaa.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentDecision {

    public enum Action {
        ROUTE_RAG,
        ROUTE_TOOL,
        CLARIFY,
        CRISIS
    }

    public enum Strategy {
        HYBRID,       // 向量+文本融合
        PRECISION,    // 仅文本/精确过滤
        CLARIFY_ONLY  // 只澄清不检索
    }

    private String level1;
    private String level2;
    private Double confidence;
    private String toolName;
    private String clarifyQuestion;
    private Action action;
    private Strategy strategy = Strategy.HYBRID;
    private String promptTemplate;
    private String knowledgeBaseId;
}
