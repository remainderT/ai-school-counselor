package org.buaa.rag.service;

import java.util.List;
import java.util.Map;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.core.model.FeedbackRequest;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.chat.StreamChatCallback;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天服务接口
 */
public interface ChatService {

    /**
     * 处理聊天流式请求：创建 SSE 连接并在异步线程中运行完整对话流程。
     *
     * <p>内部会创建 {@link StreamChatCallback} 与 SSE 生命周期绑定，
     * Controller 不再需要感知任何 SSE 事件细节。
     */
    SseEmitter handleChatStream(String message, Long userId);

    /**
     * 核心流式对话：使用调用方提供的回调驱动完整在线链路。
     * 适用于测试或其他传输层（WebSocket 等）复用此流程。
     */
    void streamChat(String message, Long userId, String taskId, StreamChatCallback callback);

    /**
     * 处理搜索请求
     */
    Result<List<RetrievalMatch>> handleSearchRequest(String query,
                                                     int topK,
                                                     Long userId);

    /**
     * 处理反馈请求
     */
    Result<Map<String, Object>> handleFeedback(FeedbackRequest request);

    /**
     * 查询在线链路轻量指标汇总
     */
    Result<Map<String, Object>> queryTraceMetricSummary(int days, Long userId);
}
