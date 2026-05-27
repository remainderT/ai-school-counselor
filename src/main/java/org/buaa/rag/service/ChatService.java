package org.buaa.rag.service;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Chat 应用服务接口：协调 SSE 流式对话和独立搜索请求。
 * <p>
 * 将原 {@code core.online.chat.ChatFacade} 提升为标准 Service 层接口，
 * 使 Controller 无需越级依赖 core 层。
 */
public interface ChatService {

    /**
     * 创建 SSE 连接并异步启动流式对话。
     */
    SseEmitter handleChatStream(String message, Long userId);

    /**
     * 处理搜索请求，返回检索结果列表。
     * mode: es(纯BM25) / vector(纯向量) / hybrid(混合无重排) / full(完整流程含意图路由+重排)
     */
    List<RetrievalMatch> handleSearchRequest(String query, int topK, Long userId);

    List<RetrievalMatch> handleSearchRequest(String query, int topK, Long userId, String mode);
}
