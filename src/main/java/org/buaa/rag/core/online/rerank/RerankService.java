package org.buaa.rag.core.online.rerank;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;

/**
 * Rerank 服务接口：对检索候选文档进行精排，
 * 按与 query 的相关度重新排序，并只返回前 topN 条。
 */
public interface RerankService {

    /**
     * 对候选文档进行精排。
     *
     * @param query      用户问题
     * @param candidates 检索候选文档列表
     * @param topN       最终保留条数
     * @return 精排后的前 topN 条文档
     */
    List<RetrievalMatch> rerank(String query, List<RetrievalMatch> candidates, int topN);
}
