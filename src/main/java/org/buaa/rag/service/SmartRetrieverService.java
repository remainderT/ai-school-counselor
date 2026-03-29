package org.buaa.rag.service;

import java.util.List;
import java.util.Set;

import org.buaa.rag.core.model.RetrievalMatch;

/**
 * 智能检索服务接口
 * 结合向量检索和文本匹配的混合搜索策略
 */
public interface SmartRetrieverService {

    /**
     * 执行混合检索
     *
     * @param queryText 查询文本
     * @param topK      返回结果数量
     * @return 检索匹配结果列表
     */
    List<RetrievalMatch> retrieve(String queryText, int topK);

    /**
     * 执行混合检索（带权限过滤）
     *
     * @param queryText 查询文本
     * @param topK      返回结果数量
     * @param userId    用户标识
     * @return 检索匹配结果列表
     */
    List<RetrievalMatch> retrieve(String queryText, int topK, String userId);

    /**
     * 向量检索（仅向量召回）
     *
     * @param queryText 查询文本
     * @param topK      返回结果数量
     * @param userId    用户标识
     * @return 检索匹配结果列表
     */
    List<RetrievalMatch> retrieveVectorOnly(String queryText, int topK, String userId);

    /**
     * 纯文本检索（显式使用）
     */
    List<RetrievalMatch> retrieveTextOnly(String queryText,
                                          int topK,
                                          String userId);

    /**
     * 限定知识库范围的检索
     */
    List<RetrievalMatch> retrieveScoped(String queryText,
                                        int topK,
                                        String userId,
                                        Set<Long> knowledgeIds);

    /**
     * 记录用户反馈
     */
    void recordFeedback(Long messageId, String userId, int score, String comment);
}
