package org.buaa.rag.service.impl;

import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.MESSAGE_EMPTY;
import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.MESSAGE_ID_REQUIRED;
import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.SCORE_OUT_OF_RANGE;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.core.model.FeedbackRequest;
import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.chat.SseStreamChatEventHandler;
import org.buaa.rag.core.online.chat.StreamChatCallback;
import org.buaa.rag.core.online.chat.StreamChatPipeline;
import org.buaa.rag.core.online.chat.StreamTaskManager;
import org.buaa.rag.core.online.intent.IntentRouterService;
import org.buaa.rag.core.online.retrieval.MultiChannelRetrievalEngine;
import org.buaa.rag.core.online.retrieval.SmartRetrieverServiceImpl;
import org.buaa.rag.core.online.retrieval.postprocessor.RetrievalPostProcessorServiceImpl;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.core.trace.RagTraceRoot;
import org.buaa.rag.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);
    private static final Pattern CITATION_INDEX_PATTERN = Pattern.compile("\\[(\\d+)]");

    private final SmartRetrieverServiceImpl retrieverService;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final RetrievalPostProcessorServiceImpl postProcessorService;
    private final IntentRouterService intentRouterService;
    private final ConversationServiceImpl conversationService;
    private final StreamChatPipeline streamChatPipeline;
    private final StreamTaskManager streamTaskManager;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    @Qualifier("chatStreamExecutor")
    private final Executor chatStreamExecutor;

    // ──────────────────────── handleChatStream ────────────────────────

    @Override
    public SseEmitter handleChatStream(String message, Long userId) {
        SseEmitter emitter = new SseEmitter(0L);

        if (isBlankString(message)) {
            try {
                emitter.send(SseEmitter.event().name("error").data(MESSAGE_EMPTY.message()));
            } catch (Exception ignored) {
            } finally {
                emitter.complete();
            }
            return emitter;
        }

        Long resolvedUserId = resolveUserId(userId);
        String taskId = UUID.randomUUID().toString();

        // 创建 SSE 事件处理器（封装所有 SSE 事件细节）
        SseStreamChatEventHandler eventHandler = new SseStreamChatEventHandler(emitter, objectMapper);

        // SSE 断开时标记取消，并触发 StreamTaskManager 清理
        emitter.onCompletion(() -> {
            eventHandler.markCancelled();
            streamTaskManager.cancel(taskId);
        });
        emitter.onTimeout(() -> {
            eventHandler.markCancelled();
            streamTaskManager.cancel(taskId);
        });

        CompletableFuture.runAsync(
            () -> streamChat(message, resolvedUserId, taskId, eventHandler),
            chatStreamExecutor
        );

        return emitter;
    }

    // ──────────────────────── streamChat（核心流程） ────────────────────────

    @Override
    @RagTraceRoot(name = "stream-chat", taskIdArg = "taskId")
    public void streamChat(String message, Long userId, String taskId, StreamChatCallback callback) {
        StreamChatPipeline.ExecutionResult executionResult = null;
        String sessionId = null;
        Long assistantMessageId = null;

        try {
            StreamChatPipeline.Context ctx = StreamChatPipeline.Context.builder()
                .message(message)
                .userId(resolveUserId(userId))
                .taskId(taskId)
                .callback(callback)
                .build();
            executionResult = streamChatPipeline.execute(ctx);
            sessionId = ctx.getSessionId();
            assistantMessageId = ctx.getAssistantMessageId();

        } catch (Exception e) {
            log.error("流式对话处理异常, userId={}, taskId={}", userId, taskId, e);
            // 回滚 assistant 占位符
            if (sessionId != null && assistantMessageId != null) {
                try {
                    conversationService.failAssistantMessage(sessionId, assistantMessageId, userId, "对话服务异常，请稍后重试。");
                } catch (Exception ignored) {
                }
            }
            callback.onError(e);
        } finally {
            streamTaskManager.unbind(taskId);
        }
    }

    // ──────────────────────── handleSearchRequest ────────────────────────

    @Override
    public Result<List<RetrievalMatch>> handleSearchRequest(String query, int topK, Long userId) {
        String userIdStr = String.valueOf(resolveUserId(userId));
        IntentDecision decision = null;
        try {
            IntentDecision raw = intentRouterService.decide(userIdStr, query);
            if (raw != null && raw.getAction() == IntentDecision.Action.ROUTE_RAG) {
                decision = raw;
            }
        } catch (Exception e) {
            log.debug("搜索接口意图识别失败，降级到全局检索: {}", e.getMessage());
        }

        List<RetrievalMatch> results = multiChannelRetrievalEngine.retrieve(userIdStr, query, topK, decision);
        if (results.isEmpty()) {
            results = retrieverService.retrieve(query, topK, userIdStr);
            results = postProcessorService.rerank(query, results, topK);
        }
        return Results.success(results);
    }

    // ──────────────────────── handleFeedback ────────────────────────

    @Override
    public Result<Map<String, Object>> handleFeedback(FeedbackRequest request) {
        if (request == null || request.getMessageId() == null) {
            throw new ClientException(MESSAGE_ID_REQUIRED);
        }

        int score = request.getScore() == null ? 0 : request.getScore();
        if (score < 1 || score > 5) {
            throw new ClientException(SCORE_OUT_OF_RANGE);
        }

        retrieverService.recordFeedback(request.getMessageId(), score);
        return Results.success(Map.of("messageId", request.getMessageId(), "score", score));
    }

    // ──────────────────────── private helpers ────────────────────────

    private double computeCitationRate(String answer, List<RetrievalMatch> sources) {
        if (answer == null || answer.isBlank() || sources == null || sources.isEmpty()) {
            return 0.0;
        }
        Matcher matcher = CITATION_INDEX_PATTERN.matcher(answer);
        Set<Integer> cited = new LinkedHashSet<>();
        int maxIndex = Math.min(ragProperties.getPrompt().getMaxSourceReferenceCount(), sources.size());
        while (matcher.find()) {
            try {
                int index = Integer.parseInt(matcher.group(1));
                if (index >= 1 && index <= maxIndex) {
                    cited.add(index);
                }
            } catch (Exception ignored) {
            }
        }
        return Math.max(0.0, Math.min(1.0, cited.size() * 1.0 / maxIndex));
    }

    private Long resolveUserId(Long userId) {
        return userId != null ? userId : UserContext.resolvedUserId();
    }

    private double round4(double value) {
        return Math.round(value * 10000.0D) / 10000.0D;
    }

    private boolean isBlankString(String str) {
        return str == null || str.isBlank();
    }
}
