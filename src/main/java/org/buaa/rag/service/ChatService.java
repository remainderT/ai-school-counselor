package org.buaa.rag.service;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.chat.StreamChatCallback;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ChatService {

    /**
     * 处理聊天流式请求
     */
    SseEmitter handleChatStream(String message, Long userId);

    /**
     * 执行核心流式对话流程
     */
    void streamChat(String message, Long userId, String taskId, StreamChatCallback callback);

    /**
     * 处理搜索请求，返回检索结果列表
     */
    List<RetrievalMatch> handleSearchRequest(String query, int topK, Long userId);
}
