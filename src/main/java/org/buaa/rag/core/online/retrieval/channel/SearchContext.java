package org.buaa.rag.core.online.retrieval.channel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.core.model.IntentDecision;

import lombok.Builder;
import lombok.Data;

/**
 * 多通道检索的统一请求上下文。
 *
 * <p>由引擎创建后传入各通道和后处理器，提供查询文本、意图判定、
 * 检索参数等信息。通道可通过 {@link #resolvedQuery()} 获取最终
 * 应用于向量/文本搜索的查询文本（优先使用改写版本）。
 */
@Data
@Builder
public class SearchContext {

    /** 发起检索的用户标识 */
    private String userId;

    /** 用户原始输入文本 */
    private String originalQuery;

    /** 经过改写/补全后的查询文本（可为空，为空时退化为原始输入） */
    private String rewrittenQuery;

    /** 期望返回的最大结果数 */
    private int topK;

    /** 首选意图（单意图场景使用） */
    private IntentDecision intentDecision;

    /** 多意图候选列表（多子问题场景使用） */
    @Builder.Default
    private List<IntentDecision> intentDecisions = List.of();

    /** 可扩展的上下文附加数据 */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 返回用于检索的最终查询文本。
     * <p>优先使用经过改写的版本，若为空则回退至原始查询。
     */
    public String resolvedQuery() {
        return (rewrittenQuery != null && !rewrittenQuery.isBlank())
                ? rewrittenQuery
                : originalQuery;
    }

    /**
     * 复制当前上下文并使用新的 topK 值（用于通道内 topK 扩大场景）。
     */
    public SearchContext withTopK(int newTopK) {
        return SearchContext.builder()
                .userId(userId)
                .originalQuery(originalQuery)
                .rewrittenQuery(rewrittenQuery)
                .topK(newTopK)
                .intentDecision(intentDecision)
                .intentDecisions(intentDecisions)
                .metadata(metadata)
                .build();
    }
}
