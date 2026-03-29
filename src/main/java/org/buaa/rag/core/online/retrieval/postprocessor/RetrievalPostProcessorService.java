package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.List;

import org.buaa.rag.core.model.CragDecision;
import org.buaa.rag.core.model.RetrievalMatch;

/**
 * 检索后处理服务接口
 * 包含检索质量评估与重排
 */
public interface RetrievalPostProcessorService {

    /**
     * 评估检索结果质量
     */
    CragDecision evaluate(String query, List<RetrievalMatch> matches);

    /**
     * 获取无结果提示信息
     */
    String noResultMessage();

    /**
     * 对检索结果进行重排
     */
    List<RetrievalMatch> rerank(String query, List<RetrievalMatch> matches, int topK);
}
