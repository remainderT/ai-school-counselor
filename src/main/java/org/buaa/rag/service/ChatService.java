package org.buaa.rag.service;

import java.util.List;
import java.util.Map;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.core.model.FeedbackRequest;
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
     * 处理搜索请求
     */
    Result<List<RetrievalMatch>> handleSearchRequest(String query, int topK, Long userId);

    /**
     * 处理用户反馈
     */
    Result<Map<String, Object>> handleFeedback(FeedbackRequest request);
}
