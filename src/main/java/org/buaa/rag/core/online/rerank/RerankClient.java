package org.buaa.rag.core.online.rerank;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;

/**
 * Rerank 客户端接口：抽象不同 Rerank 提供商的实现。
 * <p>
 * 每个实现通过 {@link #provider()} 返回自身的提供商标识，
 * {@link RoutingRerankService} 据此路由到对应的客户端。
 */
public interface RerankClient {

    /**
     * 提供商标识，如 "dashscope"、"llm"、"noop"。
     */
    String provider();

    /**
     * 对候选文档进行精排。
     *
     * @param query      用户问题
     * @param candidates 候选文档列表
     * @param topN       最终保留条数
     * @return 精排后的文档列表
     */
    List<RetrievalMatch> rerank(String query, List<RetrievalMatch> candidates, int topN);
}
