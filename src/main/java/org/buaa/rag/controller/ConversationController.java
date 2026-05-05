package org.buaa.rag.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.service.ChatService;
import org.buaa.rag.dto.req.ConversationSessionCreateReqDTO;
import org.buaa.rag.dto.req.ConversationSessionRenameReqDTO;
import org.buaa.rag.dto.resp.ConversationMessageRespDTO;
import org.buaa.rag.dto.resp.ConversationSessionRespDTO;
import org.buaa.rag.service.ConversationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/rag/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatService chatService;

    @GetMapping("/sessions")
    public Result<List<ConversationSessionRespDTO>> listSessions() {
        return Results.success(conversationService.listSessions(UserContext.resolvedUserId()));
    }

    @PostMapping("/sessions")
    public Result<ConversationSessionRespDTO> createSession(
            @Valid @RequestBody ConversationSessionCreateReqDTO request) {
        return Results.success(conversationService.createSession(UserContext.resolvedUserId(), request.getTitle()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        conversationService.deleteSession(sessionId, UserContext.resolvedUserId());
        return Results.success();
    }

    @PutMapping("/sessions/{sessionId}")
    public Result<ConversationSessionRespDTO> renameSession(
            @PathVariable String sessionId,
            @Valid @RequestBody ConversationSessionRenameReqDTO request) {
        return Results.success(conversationService.renameSession(sessionId, UserContext.resolvedUserId(), request.getTitle()));
    }

    @GetMapping("/history")
    public Result<List<ConversationMessageRespDTO>> listHistory(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "120") int limit) {
        // 传入 userId 校验会话归属，防止水平越权
        return Results.success(conversationService.listMessages(sessionId, UserContext.resolvedUserId(), limit));
    }

    // ──────────────────────── Chat 端点 ────────────────────────

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handleChatStream(@RequestParam String message) {
        return chatService.handleChatStream(message, UserContext.resolvedUserId());
    }

    @GetMapping("/search")
    public Result<List<RetrievalMatch>> handleSearchRequest(@RequestParam String query,
                                                            @RequestParam(defaultValue = "10") int topK) {
        return Results.success(chatService.handleSearchRequest(query, topK, UserContext.resolvedUserId()));
    }
}
