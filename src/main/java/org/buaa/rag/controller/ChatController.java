package org.buaa.rag.controller;

import java.util.List;
import java.util.Map;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.core.model.FeedbackRequest;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/rag/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handleChatStream(@RequestParam String message,
                                       @RequestParam(required = false) Long userId) {
        return chatService.handleChatStream(message, resolveUserId(userId));
    }

    @GetMapping("/search")
    public Result<List<RetrievalMatch>> handleSearchRequest(@RequestParam String query,
                                                            @RequestParam(defaultValue = "10") int topK,
                                                            @RequestParam(required = false) Long userId) {
        return chatService.handleSearchRequest(query, topK, resolveUserId(userId));
    }

    @PostMapping("/feedback")
    public Result<Map<String, Object>> handleFeedback(@RequestBody FeedbackRequest request) {
        return chatService.handleFeedback(request);
    }

    @GetMapping("/metrics/summary")
    public Result<Map<String, Object>> queryTraceMetricSummary(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) Long userId) {
        return chatService.queryTraceMetricSummary(days, resolveUserId(userId));
    }

    /**
     * 优先从 JWT 上下文获取用户 ID，回退到请求参数。
     */
    private Long resolveUserId(Long paramUserId) {
        Long contextUserId = UserContext.getUserId();
        if (contextUserId != null) {
            return contextUserId;
        }
        return paramUserId;
    }
}
