package org.buaa.rag.controller;

import java.util.List;
import java.util.Map;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.core.model.FeedbackRequest;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dto.req.ConversationSessionCreateReqDTO;
import org.buaa.rag.dto.req.ConversationSessionRenameReqDTO;
import org.buaa.rag.service.ChatService;
import org.buaa.rag.service.ConversationService;
import org.buaa.rag.dto.resp.ConversationMessageRespDTO;
import org.buaa.rag.dto.resp.ConversationSessionRespDTO;
import org.buaa.rag.common.convention.result.Results;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.util.StringUtils;

@RestController
@RequestMapping("/api/rag/chat")
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;

    public ChatController(ChatService chatService, ConversationService conversationService) {
        this.chatService = chatService;
        this.conversationService = conversationService;
    }

    @PostMapping("/chat")
    public Result<Map<String, Object>> handleChatRequest(@RequestBody Map<String, String> payload) {
        return chatService.handleChatRequest(payload);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
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

    @GetMapping("/sessions")
    public Result<List<ConversationSessionRespDTO>> listSessions() {
        return Results.success(conversationService.listSessions(resolveSessionOwnerPrefix()));
    }

    @PostMapping("/sessions")
    public Result<ConversationSessionRespDTO> createSession(
            @RequestBody ConversationSessionCreateReqDTO request) {
        return Results.success(conversationService.createSession(resolveSessionOwnerPrefix(), request.getTitle()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(
            @PathVariable String sessionId) {
        conversationService.deleteSession(sessionId, resolveSessionOwnerPrefix());
        return Results.success();
    }

    @PutMapping("/sessions/{sessionId}")
    public Result<ConversationSessionRespDTO> renameSession(
            @PathVariable String sessionId,
            @RequestBody ConversationSessionRenameReqDTO request) {
        return Results.success(conversationService.renameSession(sessionId, resolveSessionOwnerPrefix(), request.getTitle()));
    }

    @GetMapping("/history")
    public Result<List<ConversationMessageRespDTO>> listHistory(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "120") int limit) {
        return Results.success(conversationService.listMessages(sessionId, limit));
    }

    private String resolveSessionOwnerPrefix() {
        String username = UserContext.getUsername();
        if (StringUtils.hasText(username)) {
            return username.trim();
        }
        Long userId = UserContext.getUserId();
        if (userId != null) {
            return String.valueOf(userId);
        }
        return "anonymous";
    }
}
