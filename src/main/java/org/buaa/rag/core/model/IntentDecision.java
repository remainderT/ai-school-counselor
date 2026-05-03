package org.buaa.rag.core.model;

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
        ROUTE_CHAT,
        CLARIFY
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
    private String promptSnippet;
    private Long knowledgeBaseId;

    /**
     * 意图节点级别的 topK 覆盖值。
     * 非 null 且大于 0 时优先于全局 defaultTopK，让不同意图（如高频FAQ vs 长文档）
     * 可以独立控制检索数量。参考 ragent 的 IntentNode.topK 设计。
     */
    private Integer topK;
}
