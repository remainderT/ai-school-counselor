package org.buaa.rag.controller;

import java.util.List;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dto.req.ConversationSessionCreateReqDTO;
import org.buaa.rag.dto.req.ConversationSessionRenameReqDTO;
import org.buaa.rag.dto.resp.ConversationMessageRespDTO;
import org.buaa.rag.dto.resp.ConversationSessionRespDTO;
import org.buaa.rag.service.ConversationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping("/sessions")
    public Result<List<ConversationSessionRespDTO>> listSessions() {
        return Results.success(conversationService.listSessions(resolveUserId()));
    }

    @PostMapping("/sessions")
    public Result<ConversationSessionRespDTO> createSession(
            @RequestBody ConversationSessionCreateReqDTO request) {
        return Results.success(conversationService.createSession(resolveUserId(), request.getTitle()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        conversationService.deleteSession(sessionId, resolveUserId());
        return Results.success();
    }

    @PutMapping("/sessions/{sessionId}")
    public Result<ConversationSessionRespDTO> renameSession(
            @PathVariable String sessionId,
            @RequestBody ConversationSessionRenameReqDTO request) {
        return Results.success(conversationService.renameSession(sessionId, resolveUserId(), request.getTitle()));
    }

    @GetMapping("/history")
    public Result<List<ConversationMessageRespDTO>> listHistory(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "120") int limit) {
        return Results.success(conversationService.listMessages(sessionId, limit));
    }

    /**
     * 从 JWT 上下文获取当前用户 ID。
     */
    private Long resolveUserId() {
        Long userId = UserContext.getUserId();
        return userId != null ? userId : 1L;
    }
}
