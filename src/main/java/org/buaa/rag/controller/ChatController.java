package org.buaa.rag.controller;

import java.util.List;
import java.util.Map;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.limit.LimitScope;
import org.buaa.rag.common.limit.RateLimit;
import org.buaa.rag.dto.FeedbackRequest;
import org.buaa.rag.dto.RetrievalMatch;
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

    @PostMapping("/chat")
    @RateLimit(key = "rag:chat:sync", scope = LimitScope.GLOBAL, maxRequests = 180, windowSeconds = 60)
    @RateLimit(key = "rag:chat:sync", scope = LimitScope.IP, maxRequests = 45, windowSeconds = 60)
    @RateLimit(key = "rag:chat:sync", scope = LimitScope.USER, maxRequests = 30, windowSeconds = 60)
    public Result<Map<String, Object>> handleChatRequest(@RequestBody Map<String, String> payload) {
        return chatService.handleChatRequest(payload);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(key = "rag:chat:stream", scope = LimitScope.GLOBAL, maxRequests = 300, windowSeconds = 60)
    @RateLimit(key = "rag:chat:stream", scope = LimitScope.IP, maxRequests = 80, windowSeconds = 60)
    @RateLimit(key = "rag:chat:stream", scope = LimitScope.USER, maxRequests = 50, windowSeconds = 60)
    public SseEmitter handleChatStream(@RequestParam String message,
                                       @RequestParam(defaultValue = "anonymous") String userId) {
        return chatService.handleChatStream(message, userId);
    }

    @GetMapping("/search")
    public Result<List<RetrievalMatch>> handleSearchRequest(@RequestParam String query,
                                                            @RequestParam(defaultValue = "10") int topK,
                                                            @RequestParam(required = false) String userId) {
        return chatService.handleSearchRequest(query, topK, userId);
    }

    @PostMapping("/feedback")
    public Result<Map<String, Object>> handleFeedback(@RequestBody FeedbackRequest request) {
        return chatService.handleFeedback(request);
    }
}
