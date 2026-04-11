package org.buaa.rag.service.impl;

import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.MESSAGE_EMPTY;
import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.MESSAGE_ID_REQUIRED;
import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.SCORE_OUT_OF_RANGE;

import java.time.LocalDateTime;
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
import org.buaa.rag.core.model.ChatExecutionResult;
import org.buaa.rag.core.model.ChatRoutingPlan;
import org.buaa.rag.core.online.chat.OnlineChatOrchestrator;
import org.buaa.rag.core.online.chat.SseStreamChatEventHandler;
import org.buaa.rag.core.online.chat.StreamChatCallback;
import org.buaa.rag.core.online.chat.StreamTaskManager;
import org.buaa.rag.core.online.intent.IntentRouterService;
import org.buaa.rag.core.online.retrieval.MultiChannelRetrievalEngine;
import org.buaa.rag.core.online.retrieval.SmartRetrieverService;
import org.buaa.rag.core.online.retrieval.postprocessor.RetrievalPostProcessorService;
import org.buaa.rag.dao.entity.ChatTraceMetricDO;
import org.buaa.rag.dao.mapper.ChatTraceMetricMapper;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.service.ChatService;
import org.buaa.rag.service.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);
    private static final Pattern CITATION_INDEX_PATTERN = Pattern.compile("\\[(\\d+)]");

    private final SmartRetrieverService retrieverService;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final RetrievalPostProcessorService postProcessorService;
    private final IntentRouterService intentRouterService;
    private final ConversationService conversationService;
    private final ChatTraceMetricMapper chatTraceMetricMapper;
    private final OnlineChatOrchestrator onlineChatOrchestrator;
    private final StreamTaskManager streamTaskManager;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    @Qualifier("chatStreamExecutor")
    private final Executor chatStreamExecutor;

    public ChatServiceImpl(SmartRetrieverService retrieverService,
                           MultiChannelRetrievalEngine multiChannelRetrievalEngine,
                           RetrievalPostProcessorService postProcessorService,
                           IntentRouterService intentRouterService,
                           ConversationService conversationService,
                           ChatTraceMetricMapper chatTraceMetricMapper,
                           OnlineChatOrchestrator onlineChatOrchestrator,
                           StreamTaskManager streamTaskManager,
                           ObjectMapper objectMapper,
                           RagProperties ragProperties,
                           @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.retrieverService = retrieverService;
        this.multiChannelRetrievalEngine = multiChannelRetrievalEngine;
        this.postProcessorService = postProcessorService;
        this.intentRouterService = intentRouterService;
        this.conversationService = conversationService;
        this.chatTraceMetricMapper = chatTraceMetricMapper;
        this.onlineChatOrchestrator = onlineChatOrchestrator;
        this.streamTaskManager = streamTaskManager;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
        this.chatStreamExecutor = chatStreamExecutor;
    }

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
    public void streamChat(String message, Long userId, String taskId, StreamChatCallback callback) {
        String sessionId = null;
        Long assistantMessageId = null;
        ChatExecutionResult executionResult = null;
        // 在与下游 intent/retrieval 层交互时使用字符串形式的 userId
        String userIdStr = String.valueOf(resolveUserId(userId));

        try {
            // 1) 创建/复用会话
            sessionId = conversationService.obtainOrCreateSession(userId);

            // 2) 持久化用户消息 + 创建 assistant 占位符
            conversationService.appendUserMessage(sessionId, userId, message);
            assistantMessageId = conversationService.createAssistantPlaceholder(sessionId, userId);

            // 3) 首帧立即推送元信息（前端可立即获得 messageId + taskId）
            callback.onMeta(assistantMessageId, taskId);

            // 4) 绑定取消句柄（SSE 断连时可通过 taskId 清理）
            final String capturedSessionId = sessionId;
            final Long capturedMessageId = assistantMessageId;
            final Long capturedUserId = userId;
            streamTaskManager.bindCancel(taskId, () -> {
                try {
                    conversationService.failAssistantMessage(
                        capturedSessionId, capturedMessageId, capturedUserId, "（用户已取消请求）");
                } catch (Exception ignored) {
                }
            });

            // 5) 并行加载会话上下文（摘要 + 历史）
            List<Map<String, String>> conversationHistory = conversationService.loadConversationContext(sessionId);

            // 6) 查询改写 + 子问题拆分 + 并行意图解析
            //    携带历史上下文，让改写 LLM 能消歧代词（"它"/"上面说的"等）
            ChatRoutingPlan routingPlan = onlineChatOrchestrator.rewriteSplitAndResolve(
                    userIdStr, message, conversationHistory);

            // 7) 歧义引导检测（若需要澄清则跳过检索直接回复）
            String guidanceQuestion = onlineChatOrchestrator.detectGuidanceQuestion(routingPlan);
            if (!isBlankString(guidanceQuestion)) {
                callback.onContent(guidanceQuestion);
                executionResult = new ChatExecutionResult(guidanceQuestion, List.of(), true, 0, 0, routingPlan.rewriteLatencyMs());
            } else {
                // 8) 检索 + Prompt 组装 + LLM 流式输出
                executionResult = onlineChatOrchestrator.executeWithPlan(
                    userIdStr,
                    message,
                    conversationHistory,
                    routingPlan,
                    chunk -> {
                        if (!callback.isCancelled()) {
                            callback.onContent(chunk);
                        }
                    }
                );
            }

            // 9) 持久化 assistant 消息（流式占位符 → 最终内容）
            conversationService.completeAssistantMessage(
                sessionId,
                assistantMessageId,
                userId,
                executionResult.response(),
                executionResult.sources()
            );

            // 10) 推送来源引用
            callback.onSources(executionResult.sources());

            // 11) 写入在线链路指标
            persistTraceMetric(sessionId, assistantMessageId, userId, message, executionResult);

            // 12) 正常完成
            callback.onComplete();

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

        Long userId = resolveUserId(request.getUserId());
        String userIdStr = String.valueOf(userId);

        retrieverService.recordFeedback(request.getMessageId(), userIdStr, score, request.getComment());
        chatTraceMetricMapper.update(
            ChatTraceMetricDO.builder()
                .userFeedbackScore(score)
                .build(),
            Wrappers.lambdaUpdate(ChatTraceMetricDO.class)
                .eq(ChatTraceMetricDO::getMessageId, request.getMessageId())
        );
        return Results.success(Map.of("messageId", request.getMessageId(), "score", score));
    }

    // ──────────────────────── queryTraceMetricSummary ────────────────────────

    @Override
    public Result<Map<String, Object>> queryTraceMetricSummary(int days, Long userId) {
        int windowDays = Math.max(1, Math.min(days, 365));
        LocalDateTime startTime = LocalDateTime.now().minusDays(windowDays);

        var wrapper = Wrappers.lambdaQuery(ChatTraceMetricDO.class)
            .ge(ChatTraceMetricDO::getCreatedAt, startTime)
            .orderByDesc(ChatTraceMetricDO::getCreatedAt);
        if (userId != null) {
            wrapper.eq(ChatTraceMetricDO::getUserId, userId);
        }
        List<ChatTraceMetricDO> rows = chatTraceMetricMapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return Results.success(Map.of(
                "days", windowDays,
                "sampleSize", 0,
                "avgRewriteLatencyMs", 0.0,
                "avgRetrievalHitRate", 0.0,
                "avgCitationRate", 0.0,
                "clarifyTriggerRate", 0.0,
                "avgFeedbackScore", 0.0
            ));
        }

        List<Integer> feedbackScores = rows.stream()
            .map(ChatTraceMetricDO::getUserFeedbackScore)
            .filter(java.util.Objects::nonNull)
            .toList();

        return Results.success(Map.of(
            "days", windowDays,
            "sampleSize", rows.size(),
            "avgRewriteLatencyMs", round4(avgField(rows, ChatTraceMetricDO::getRewriteLatencyMs)),
            "avgRetrievalHitRate", round4(avgField(rows, ChatTraceMetricDO::getRetrievalHitRate)),
            "avgCitationRate", round4(avgField(rows, ChatTraceMetricDO::getCitationRate)),
            "clarifyTriggerRate", round4(avgField(rows, ChatTraceMetricDO::getClarifyTriggered)),
            "avgFeedbackScore", round4(feedbackScores.stream().mapToInt(Integer::intValue).average().orElse(0.0)),
            "feedbackCoverage", round4(feedbackScores.size() * 1.0 / rows.size())
        ));
    }

    // ──────────────────────── private helpers ────────────────────────

    private void persistTraceMetric(String sessionId,
                                    Long messageId,
                                    Long userId,
                                    String query,
                                    ChatExecutionResult result) {
        if (messageId == null || result == null) {
            return;
        }
        try {
            ChatTraceMetricDO metric = ChatTraceMetricDO.builder()
                .sessionId(sessionId)
                .messageId(messageId)
                .userId(resolveUserId(userId))
                .queryText(query)
                .rewriteLatencyMs(Math.max(0, result.rewriteLatencyMs()))
                .retrievalHitRate(result.retrievalTopK() <= 0 ? 0.0 :
                    Math.max(0.0, Math.min(1.0, result.retrievedCount() * 1.0 / result.retrievalTopK())))
                .citationRate(computeCitationRate(result.response(), result.sources()))
                .clarifyTriggered(result.clarifyTriggered() ? 1 : 0)
                .build();
            chatTraceMetricMapper.insert(metric);
        } catch (Exception e) {
            log.debug("写入在线链路指标失败, messageId={}, error={}", messageId, e.getMessage());
        }
    }

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

    private <T extends Number> double avgField(List<ChatTraceMetricDO> rows,
                                               java.util.function.Function<ChatTraceMetricDO, T> getter) {
        return rows.stream()
            .map(getter)
            .filter(java.util.Objects::nonNull)
            .mapToDouble(Number::doubleValue)
            .average()
            .orElse(0.0);
    }

    private double round4(double value) {
        return Math.round(value * 10000.0D) / 10000.0D;
    }

    private boolean isBlankString(String str) {
        return str == null || str.isBlank();
    }
}
