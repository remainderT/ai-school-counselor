package org.buaa.rag.service;

import java.util.List;
import java.util.Map;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.core.model.FeedbackRequest;
import org.buaa.rag.core.model.RetrievalMatch;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天服务接口
 */
public interface ChatService {

    /**
     * 处理聊天请求
     */
    Result<Map<String, Object>> handleChatRequest(Map<String, String> payload);

    /**
     * 处理聊天流式请求
     */
    SseEmitter handleChatStream(String message, String userId);

    /**
     * 处理搜索请求
     */
    Result<List<RetrievalMatch>> handleSearchRequest(String query,
                                                     int topK,
                                                     String userId);

    /**
     * 处理反馈请求
     */
    Result<Map<String, Object>> handleFeedback(FeedbackRequest request);

    /**
     * 查询在线链路轻量指标汇总
     */
    Result<Map<String, Object>> queryTraceMetricSummary(int days, String userId);
}
