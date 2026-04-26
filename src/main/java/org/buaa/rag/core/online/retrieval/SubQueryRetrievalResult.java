package org.buaa.rag.core.online.retrieval;

import java.util.List;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.RetrievalMatch;

/**
 * 单个子问题的完整检索结果，供在线执行链路复用。
 *
 * @param query           子问题文本
 * @param intent          命中的主意图
 * @param sources         检索到的上下文 chunks（ROUTE_RAG 路径）
 * @param clarifyTriggered 是否触发了澄清
 * @param clarifyMessage  澄清消息（clarifyTriggered=true 时非空）
 * @param directResponse  直接回复文本（ROUTE_TOOL/CLARIFY 路径，RAG 路径为 null）
 * @param retrievedCount  实际检索到的 chunk 数量
 * @param retrievalTopK   本次检索目标 TopK
 */
public record SubQueryRetrievalResult(
        String query,
        IntentDecision intent,
        List<RetrievalMatch> sources,
        boolean clarifyTriggered,
        String clarifyMessage,
        String directResponse,
        int retrievedCount,
        int retrievalTopK
) {
}
