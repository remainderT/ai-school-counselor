package org.buaa.rag.service.impl;

import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.MESSAGE_EMPTY;
import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.MESSAGE_ID_REQUIRED;
import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.SCORE_OUT_OF_RANGE;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.core.online.intent.IntentPatternService;
import org.buaa.rag.core.online.intent.IntentResolutionService;
import org.buaa.rag.core.online.intent.IntentRouterService;
import org.buaa.rag.core.online.intent.SubQueryIntent;
import org.buaa.rag.core.online.cache.SemanticCacheService;
import org.buaa.rag.core.online.query.QueryAnalysisService;
import org.buaa.rag.core.online.query.QueryDecomposer;
import org.buaa.rag.core.online.tool.ToolService;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.core.model.CragDecision;
import org.buaa.rag.core.model.FeedbackRequest;
import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.QueryPlan;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.dao.entity.ChatTraceMetricDO;
import org.buaa.rag.dao.mapper.ChatTraceMetricMapper;
import org.buaa.rag.service.ChatService;
import org.buaa.rag.service.ConversationService;
import org.buaa.rag.core.online.retrieval.postprocessor.RetrievalPostProcessorService;
import org.buaa.rag.service.SmartRetrieverService;
import org.buaa.rag.core.online.retrieval.MultiChannelRetrievalEngine;
import org.buaa.rag.tool.LlmChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private static final String DEFAULT_USER_ID = "anonymous";
    private static final int MAX_REFERENCE_LENGTH = 300;
    private static final int MAX_SOURCE_REFERENCE_COUNT = 5;
    private static final int DEFAULT_RETRIEVAL_K = 5;
    private static final int MAX_RETRIEVAL_K = 10;
    private static final double MIN_ACCEPTABLE_SCORE = 0.25;
    private static final Pattern CITATION_INDEX_PATTERN = Pattern.compile("\\[(\\d+)]");

    private final SmartRetrieverService retrieverService;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final RetrievalPostProcessorService postProcessorService;
    private final LlmChat llmService;
    private final QueryAnalysisService queryAnalysisService;
    private final IntentRouterService intentRouterService;
    private final IntentResolutionService intentResolutionService;
    private final ToolService toolService;
    private final IntentPatternService intentPatternService;
    private final QueryDecomposer queryDecomposer;
    private final RagProperties ragProperties;
    private final SemanticCacheService semanticCacheService;
    private final ConversationService conversationService;
    private final ChatTraceMetricMapper chatTraceMetricMapper;
    @Qualifier("chatStreamExecutor")
    private final Executor chatStreamExecutor;

    private record RoutingContext(String rewrittenQuery, List<SubQueryIntent> subQueryIntents) {
    }

    private record StreamExecutionResult(String response,
                                         List<RetrievalMatch> sources,
                                         boolean clarifyTriggered,
                                         int retrievedCount,
                                         int retrievalTopK) {
    }

    private record SubQueryRetrievalResult(String query,
                                           IntentDecision intent,
                                           List<RetrievalMatch> sources,
                                           boolean clarifyTriggered,
                                           String clarifyMessage,
                                           int retrievedCount,
                                           int retrievalTopK) {
    }

    @Override
    public Result<Map<String, Object>> handleChatRequest(Map<String, String> payload) {
        String userMessage = payload == null ? null : payload.get("message");
        String userId = payload == null ? DEFAULT_USER_ID : payload.getOrDefault("userId", DEFAULT_USER_ID);
        if (isBlankString(userMessage)) {
            throw new ClientException(MESSAGE_EMPTY);
        }
        String resolvedUserId = isBlankString(userId) ? DEFAULT_USER_ID : userId;
        String sessionId = conversationService.obtainOrCreateSession(resolvedUserId);
        List<Map<String, String>> conversationHistory = conversationService.loadConversationContext(sessionId);

        long rewriteStartNanos = System.nanoTime();
        RoutingContext routingContext = prepareRoutingContext(resolvedUserId, userMessage);
        long rewriteLatencyMs = nanosToMillis(System.nanoTime() - rewriteStartNanos);

        List<SubQueryIntent> resolvedSubQueries = routingContext.subQueryIntents();
        String rewrittenQuery = routingContext.rewrittenQuery();

        StreamExecutionResult executionResult;
        if (resolvedSubQueries.size() > 1) {
            executionResult = streamMultiIntentRoute(
                    resolvedUserId,
                    userMessage,
                    resolvedSubQueries,
                    conversationHistory,
                    null
            );
        } else {
            IntentDecision primaryIntent = resolvedSubQueries.isEmpty()
                    ? defaultRagIntent()
                    : selectPrimaryIntent(resolvedSubQueries.get(0));
            executionResult = streamIntentRoute(
                    resolvedUserId,
                    rewrittenQuery,
                    primaryIntent,
                    conversationHistory,
                    null
            );
            recordHighConfidencePattern(primaryIntent, rewrittenQuery);
        }

        Long messageId = conversationService.appendToHistory(
                sessionId,
                resolvedUserId,
                userMessage,
                executionResult.response(),
                executionResult.sources()
        );
        persistTraceMetric(
                sessionId,
                messageId,
                resolvedUserId,
                userMessage,
                rewriteLatencyMs,
                executionResult
        );
        return Results.success(Map.of(
                "response", executionResult.response(),
                "sources", executionResult.sources(),
                "messageId", messageId
        ));
    }

    @Override
    public SseEmitter handleChatStream(String message, String userId) {
        SseEmitter emitter = new SseEmitter(0L);

        if (isBlankString(message)) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(MESSAGE_EMPTY.message()));
            } catch (Exception ignored) {
            } finally {
                emitter.complete();
            }
            return emitter;
        }

        String resolvedUserId = isBlankString(userId) ? DEFAULT_USER_ID : userId;
        CompletableFuture.runAsync(() -> handleMessageStreamInternal(
                        resolvedUserId,
                        message,
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().name("message").data(chunk));
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            try {
                                emitter.send(SseEmitter.event().name("error")
                                        .data("对话服务异常: " + error.getMessage()));
                            } catch (Exception ignored) {
                            } finally {
                                emitter.completeWithError(error);
                            }
                        },
                        sources -> {
                            try {
                                emitter.send(SseEmitter.event().name("sources").data(sources));
                            } catch (Exception ignored) {
                            }
                        },
                        messageId -> {
                            try {
                                emitter.send(SseEmitter.event().name("messageId").data(messageId));
                            } catch (Exception ignored) {
                            }
                        },
                        () -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data(""));
                            } catch (Exception ignored) {
                            } finally {
                                emitter.complete();
                            }
                        }
                ),
                chatStreamExecutor
        );

        return emitter;
    }

    @Override
    public Result<List<RetrievalMatch>> handleSearchRequest(String query,
                                                            int topK,
                                                            String userId) {
        IntentDecision decision = null;
        try {
            IntentDecision raw = intentRouterService.decide(userId, query);
            if (raw != null && raw.getAction() == IntentDecision.Action.ROUTE_RAG) {
                decision = raw;
            }
        } catch (Exception e) {
            log.debug("搜索接口意图识别失败，降级到全局检索: {}", e.getMessage());
        }

        List<RetrievalMatch> results = multiChannelRetrievalEngine.retrieve(userId, query, topK, decision);
        if (results.isEmpty()) {
            results = retrieverService.retrieve(query, topK, userId);
            results = postProcessorService.rerank(query, results, topK);
        }
        return Results.success(results);
    }

    @Override
    public Result<Map<String, Object>> handleFeedback(FeedbackRequest request) {
        if (request == null || request.getMessageId() == null) {
            throw new ClientException(MESSAGE_ID_REQUIRED);
        }

        int score = request.getScore() == null ? 0 : request.getScore();
        if (score < 1 || score > 5) {
            throw new ClientException(SCORE_OUT_OF_RANGE);
        }

        String userId = request.getUserId();
        if (isBlankString(userId)) {
            userId = DEFAULT_USER_ID;
        }

        retrieverService.recordFeedback(request.getMessageId(), userId, score, request.getComment());
        chatTraceMetricMapper.update(
                ChatTraceMetricDO.builder()
                        .userFeedbackScore(score)
                        .build(),
                Wrappers.lambdaUpdate(ChatTraceMetricDO.class)
                        .eq(ChatTraceMetricDO::getMessageId, request.getMessageId())
        );
        return Results.success(Map.of("messageId", request.getMessageId(), "score", score));
    }

    @Override
    public Result<Map<String, Object>> queryTraceMetricSummary(int days, String userId) {
        int windowDays = Math.max(1, Math.min(days, 365));
        LocalDateTime startTime = LocalDateTime.now().minusDays(windowDays);

        var wrapper = Wrappers.lambdaQuery(ChatTraceMetricDO.class)
                .ge(ChatTraceMetricDO::getCreatedAt, startTime)
                .orderByDesc(ChatTraceMetricDO::getCreatedAt);
        if (!isBlankString(userId)) {
            wrapper.eq(ChatTraceMetricDO::getUserId, userId.trim());
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

        double avgRewriteLatencyMs = rows.stream()
                .map(ChatTraceMetricDO::getRewriteLatencyMs)
                .filter(v -> v != null)
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
        double avgRetrievalHitRate = rows.stream()
                .map(ChatTraceMetricDO::getRetrievalHitRate)
                .filter(v -> v != null)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
        double avgCitationRate = rows.stream()
                .map(ChatTraceMetricDO::getCitationRate)
                .filter(v -> v != null)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
        double clarifyTriggerRate = rows.stream()
                .map(ChatTraceMetricDO::getClarifyTriggered)
                .filter(v -> v != null)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);
        List<Integer> feedbackScores = rows.stream()
                .map(ChatTraceMetricDO::getUserFeedbackScore)
                .filter(v -> v != null)
                .toList();
        double avgFeedbackScore = feedbackScores.stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);

        return Results.success(Map.of(
                "days", windowDays,
                "sampleSize", rows.size(),
                "avgRewriteLatencyMs", round4(avgRewriteLatencyMs),
                "avgRetrievalHitRate", round4(avgRetrievalHitRate),
                "avgCitationRate", round4(avgCitationRate),
                "clarifyTriggerRate", round4(clarifyTriggerRate),
                "avgFeedbackScore", round4(avgFeedbackScore),
                "feedbackCoverage", round4(feedbackScores.size() * 1.0 / rows.size())
        ));
    }


    private void handleMessageStreamInternal(String userId,
                                             String userMessage,
                                             Consumer<String> chunkHandler,
                                             Consumer<Throwable> errorHandler,
                                             Consumer<List<?>> sourcesHandler,
                                             Consumer<Long> messageIdHandler,
                                             Runnable completionHandler) {
        String sessionId = null;
        Long assistantMessageId = null;
        try {
            sessionId = conversationService.obtainOrCreateSession(userId);
            conversationService.appendUserMessage(sessionId, userId, userMessage);
            assistantMessageId = conversationService.createAssistantPlaceholder(sessionId, userId);
            if (messageIdHandler != null && assistantMessageId != null) {
                messageIdHandler.accept(assistantMessageId);
            }

            List<Map<String, String>> conversationHistory = conversationService.loadConversationContext(sessionId);
            long rewriteStartNanos = System.nanoTime();
            RoutingContext routingContext = prepareRoutingContext(userId, userMessage);
            long rewriteLatencyMs = nanosToMillis(System.nanoTime() - rewriteStartNanos);
            List<SubQueryIntent> resolvedSubQueries = routingContext.subQueryIntents();
            String rewrittenQuery = routingContext.rewrittenQuery();
            if (resolvedSubQueries.size() > 1) {
                StreamExecutionResult multiResult = streamMultiIntentRoute(
                        userId,
                        userMessage,
                        resolvedSubQueries,
                        conversationHistory,
                        chunkHandler
                );
                conversationService.completeAssistantMessage(
                        sessionId,
                        assistantMessageId,
                        multiResult.response(),
                        multiResult.sources()
                );
                persistTraceMetric(
                        sessionId,
                        assistantMessageId,
                        userId,
                        userMessage,
                        rewriteLatencyMs,
                        multiResult
                );
                if (sourcesHandler != null) {
                    sourcesHandler.accept(multiResult.sources());
                }
                if (completionHandler != null) {
                    completionHandler.run();
                }
                return;
            }

            IntentDecision primaryIntent = resolvedSubQueries.isEmpty()
                    ? defaultRagIntent()
                    : selectPrimaryIntent(resolvedSubQueries.get(0));
            StreamExecutionResult singleResult = streamIntentRoute(
                    userId,
                    rewrittenQuery,
                    primaryIntent,
                    conversationHistory,
                    chunkHandler
            );
            conversationService.completeAssistantMessage(
                    sessionId,
                    assistantMessageId,
                    singleResult.response(),
                    singleResult.sources()
            );
            recordHighConfidencePattern(primaryIntent, rewrittenQuery);
            persistTraceMetric(
                    sessionId,
                    assistantMessageId,
                    userId,
                    userMessage,
                    rewriteLatencyMs,
                    singleResult
            );
            if (sourcesHandler != null) {
                sourcesHandler.accept(singleResult.sources());
            }
            if (completionHandler != null) {
                completionHandler.run();
            }
        } catch (Exception e) {
            if (sessionId != null && assistantMessageId != null) {
                conversationService.failAssistantMessage(
                        sessionId,
                        assistantMessageId,
                        "对话服务异常，请稍后重试。"
                );
            }
            if (errorHandler != null) {
                errorHandler.accept(e);
            }
        }
    }

    private StreamExecutionResult streamMultiIntentRoute(String userId,
                                                         String originalQuery,
                                                         List<SubQueryIntent> resolvedSubQueries,
                                                         List<Map<String, String>> conversationHistory,
                                                         Consumer<String> chunkHandler) {
        List<SubQueryIntent> validSubQueries = resolvedSubQueries == null
                ? List.of()
                : resolvedSubQueries.stream()
                .filter(item -> item != null && !isBlankString(item.subQuery()))
                .toList();

        if (validSubQueries.isEmpty()) {
            return streamIntentRoute(
                    userId,
                    originalQuery,
                    defaultRagIntent(),
                    conversationHistory,
                    chunkHandler
            );
        }

        boolean containsNonRagAction = validSubQueries.stream()
                .map(this::selectPrimaryIntent)
                .anyMatch(intent -> intent == null
                        || intent.getAction() == null
                        || intent.getAction() != IntentDecision.Action.ROUTE_RAG);
        if (containsNonRagAction) {
            return streamMultiIntentRouteLegacy(
                    userId,
                    originalQuery,
                    validSubQueries,
                    conversationHistory,
                    chunkHandler
            );
        }

        List<CompletableFuture<SubQueryRetrievalResult>> futures = validSubQueries.stream()
                .map(subQueryIntent -> CompletableFuture.supplyAsync(
                        () -> retrieveSubQueryContext(userId, subQueryIntent),
                        chatStreamExecutor
                ))
                .toList();

        List<SubQueryRetrievalResult> retrievalResults = futures.stream()
                .map(future -> {
                    try {
                        return future.join();
                    } catch (Exception e) {
                        log.warn("多子问题并行检索失败: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(result -> result != null)
                .toList();

        List<RetrievalMatch> mergedSources = retrievalResults.stream()
                .flatMap(result -> result.sources().stream())
                .toList();
        boolean clarifyTriggered = retrievalResults.stream().anyMatch(SubQueryRetrievalResult::clarifyTriggered);
        String clarifyMessage = retrievalResults.stream()
                .map(SubQueryRetrievalResult::clarifyMessage)
                .filter(text -> !isBlankString(text))
                .findFirst()
                .orElse(null);
        int retrievedCount = retrievalResults.stream()
                .mapToInt(SubQueryRetrievalResult::retrievedCount)
                .sum();
        int retrievalTopK = retrievalResults.stream()
                .mapToInt(SubQueryRetrievalResult::retrievalTopK)
                .sum();

        if (mergedSources.isEmpty()) {
            if (!isBlankString(clarifyMessage)) {
                if (chunkHandler != null) {
                    chunkHandler.accept(clarifyMessage);
                }
                return new StreamExecutionResult(clarifyMessage, List.of(), true, 0, retrievalTopK);
            }
            return streamIntentRoute(
                    userId,
                    originalQuery,
                    defaultRagIntent(),
                    conversationHistory,
                    chunkHandler
            );
        }

        List<RetrievalMatch> deduplicated = deduplicateSources(mergedSources);
        int aggregateTopK = determineRetrievalK(originalQuery);
        List<RetrievalMatch> reranked = postProcessorService.rerank(originalQuery, deduplicated, aggregateTopK);
        CragDecision mergedDecision = postProcessorService.evaluate(originalQuery, reranked);
        if (mergedDecision.getAction() == CragDecision.Action.CLARIFY
                || mergedDecision.getAction() == CragDecision.Action.NO_ANSWER) {
            String mergedResponse = appendSourceReferences(mergedDecision.getMessage(), reranked);
            if (chunkHandler != null) {
                chunkHandler.accept(mergedResponse);
            }
            boolean triggered = mergedDecision.getAction() == CragDecision.Action.CLARIFY;
            return new StreamExecutionResult(mergedResponse, reranked, triggered, reranked.size(), aggregateTopK);
        }
        if (mergedDecision.getAction() == CragDecision.Action.REFINE) {
            List<RetrievalMatch> fallback = runFallbackRetrieval(userId, originalQuery, aggregateTopK);
            if (!fallback.isEmpty()) {
                reranked = fallback;
            } else {
                String mergedResponse = appendSourceReferences(postProcessorService.noResultMessage(), reranked);
                if (chunkHandler != null) {
                    chunkHandler.accept(mergedResponse);
                }
                return new StreamExecutionResult(mergedResponse, reranked, false, reranked.size(), aggregateTopK);
            }
        }

        String response = generateRagAnswer(
                originalQuery,
                resolvePromptTemplate(selectPrimaryIntent(validSubQueries.get(0))),
                conversationHistory,
                reranked,
                chunkHandler
        );
        semanticCacheService.put(originalQuery, response, reranked);
        int safeRetrievedCount = Math.max(retrievedCount, reranked.size());
        int safeRetrievalTopK = Math.max(retrievalTopK, aggregateTopK);
        return new StreamExecutionResult(response, reranked, clarifyTriggered, safeRetrievedCount, safeRetrievalTopK);
    }

    private StreamExecutionResult streamMultiIntentRouteLegacy(String userId,
                                                               String originalQuery,
                                                               List<SubQueryIntent> resolvedSubQueries,
                                                               List<Map<String, String>> conversationHistory,
                                                               Consumer<String> chunkHandler) {
        List<RetrievalMatch> mergedSources = new ArrayList<>();
        StringBuilder mergedResponse = new StringBuilder();
        int validCount = 0;
        boolean clarifyTriggered = false;
        int retrievedCount = 0;
        int retrievalTopK = 0;

        for (SubQueryIntent subQueryIntent : resolvedSubQueries) {
            if (subQueryIntent == null || isBlankString(subQueryIntent.subQuery())) {
                continue;
            }
            validCount++;
            String header = (mergedResponse.length() == 0 ? "" : "\n\n")
                    + "【子问题" + validCount + "】" + subQueryIntent.subQuery() + "\n";
            mergedResponse.append(header);
            if (chunkHandler != null) {
                chunkHandler.accept(header);
            }

            IntentDecision primaryIntent = selectPrimaryIntent(subQueryIntent);
            StreamExecutionResult subResult = streamIntentRoute(
                    userId,
                    subQueryIntent.subQuery(),
                    primaryIntent,
                    conversationHistory,
                    chunkHandler
            );
            mergedResponse.append(subResult.response());
            mergedSources.addAll(subResult.sources());
            clarifyTriggered = clarifyTriggered || subResult.clarifyTriggered();
            retrievedCount += subResult.retrievedCount();
            retrievalTopK += subResult.retrievalTopK();
            recordHighConfidencePattern(primaryIntent, subQueryIntent.subQuery());
        }

        if (validCount == 0) {
            return streamIntentRoute(
                    userId,
                    originalQuery,
                    defaultRagIntent(),
                    conversationHistory,
                    chunkHandler
            );
        }

        List<RetrievalMatch> deduplicated = deduplicateSources(mergedSources);
        String rawMerged = mergedResponse.toString();
        String finalResponse = appendSourceReferences(rawMerged, deduplicated);
        if (!finalResponse.equals(rawMerged)
                && finalResponse.startsWith(rawMerged)
                && chunkHandler != null) {
            chunkHandler.accept(finalResponse.substring(rawMerged.length()));
        }
        semanticCacheService.put(originalQuery, finalResponse, deduplicated);
        return new StreamExecutionResult(finalResponse, deduplicated, clarifyTriggered, retrievedCount, retrievalTopK);
    }

    private SubQueryRetrievalResult retrieveSubQueryContext(String userId, SubQueryIntent subQueryIntent) {
        String query = subQueryIntent.subQuery();
        IntentDecision primaryIntent = selectPrimaryIntent(subQueryIntent);
        int retrievalK = determineRetrievalK(query);
        List<RetrievalMatch> retrievalResults = retrieveByStrategy(userId, query, retrievalK, primaryIntent);
        CragDecision decision = postProcessorService.evaluate(query, retrievalResults);

        boolean clarifyTriggered = false;
        String clarifyMessage = null;
        if (decision.getAction() == CragDecision.Action.CLARIFY) {
            clarifyTriggered = true;
            clarifyMessage = decision.getMessage();
        } else if (decision.getAction() == CragDecision.Action.REFINE) {
            List<RetrievalMatch> fallback = runFallbackRetrieval(userId, query, retrievalK);
            if (!fallback.isEmpty()) {
                retrievalResults = fallback;
            }
        } else if (decision.getAction() == CragDecision.Action.NO_ANSWER) {
            retrievalResults = List.of();
        }

        recordHighConfidencePattern(primaryIntent, query);
        return new SubQueryRetrievalResult(
                query,
                primaryIntent,
                retrievalResults == null ? List.of() : retrievalResults,
                clarifyTriggered,
                clarifyMessage,
                retrievalResults == null ? 0 : retrievalResults.size(),
                retrievalK
        );
    }

    private StreamExecutionResult streamIntentRoute(String userId,
                                                    String query,
                                                    IntentDecision intent,
                                                    List<Map<String, String>> conversationHistory,
                                                    Consumer<String> chunkHandler) {
        IntentDecision resolvedIntent = intent == null || intent.getAction() == null
                ? defaultRagIntent()
                : intent;

        if (resolvedIntent.getAction() == IntentDecision.Action.CLARIFY) {
            String response = resolvedIntent.getClarifyQuestion() != null
                    ? resolvedIntent.getClarifyQuestion()
                    : "需要更多信息，请具体描述。";
            if (chunkHandler != null) {
                chunkHandler.accept(response);
            }
            return new StreamExecutionResult(response, List.of(), true, 0, 0);
        }

        if (resolvedIntent.getAction() == IntentDecision.Action.ROUTE_TOOL) {
            String response = toolService.execute(userId, query, resolvedIntent);
            if (chunkHandler != null) {
                chunkHandler.accept(response);
            }
            return new StreamExecutionResult(response, List.of(), false, 0, 0);
        }

        var cacheHit = semanticCacheService.find(query);
        if (cacheHit.isPresent()) {
            List<RetrievalMatch> cachedSources = cacheHit.get().sources();
            String response = appendSourceReferences(cacheHit.get().response(), cachedSources);
            if (chunkHandler != null) {
                chunkHandler.accept(response);
            }
            return new StreamExecutionResult(response, cachedSources, false, 0, 0);
        }

        String promptTemplate = resolvePromptTemplate(resolvedIntent);
        int retrievalK = determineRetrievalK(query);
        List<RetrievalMatch> retrievalResults = retrieveByStrategy(userId, query, retrievalK, resolvedIntent);

        CragDecision decision = postProcessorService.evaluate(query, retrievalResults);
        if (decision.getAction() == CragDecision.Action.CLARIFY
                || decision.getAction() == CragDecision.Action.NO_ANSWER) {
            String response = appendSourceReferences(decision.getMessage(), retrievalResults);
            if (chunkHandler != null) {
                chunkHandler.accept(response);
            }
            boolean triggered = decision.getAction() == CragDecision.Action.CLARIFY;
            return new StreamExecutionResult(response, retrievalResults, triggered, retrievalResults.size(), retrievalK);
        }

        if (decision.getAction() == CragDecision.Action.REFINE) {
            List<RetrievalMatch> fallback = runFallbackRetrieval(userId, query, retrievalK);
            if (!fallback.isEmpty()) {
                retrievalResults = fallback;
            } else {
                String response = appendSourceReferences(postProcessorService.noResultMessage(), retrievalResults);
                if (chunkHandler != null) {
                    chunkHandler.accept(response);
                }
                return new StreamExecutionResult(response, retrievalResults, false, retrievalResults.size(), retrievalK);
            }
        }

        String finalResponse = generateRagAnswer(
                query,
                promptTemplate,
                conversationHistory,
                retrievalResults,
                chunkHandler
        );
        semanticCacheService.put(query, finalResponse, retrievalResults);
        return new StreamExecutionResult(finalResponse, retrievalResults, false, retrievalResults.size(), retrievalK);
    }

    private String generateRagAnswer(String query,
                                     String promptTemplate,
                                     List<Map<String, String>> conversationHistory,
                                     List<RetrievalMatch> retrievalResults,
                                     Consumer<String> chunkHandler) {
        String referenceContext = constructReferenceContext(retrievalResults);

        StringBuilder responseBuilder = new StringBuilder();
        Throwable[] streamError = new Throwable[1];
        llmService.streamResponse(
                query,
                applyPromptTemplate(promptTemplate, referenceContext),
                conversationHistory,
                chunk -> {
                    responseBuilder.append(chunk);
                    if (chunkHandler != null) {
                        chunkHandler.accept(chunk);
                    }
                },
                error -> streamError[0] = error,
                () -> {
                }
        );
        if (streamError[0] != null) {
            throw new RuntimeException("AI服务异常: " + streamError[0].getMessage(), streamError[0]);
        }
        String rawResponse = responseBuilder.toString();
        String finalResponse = appendSourceReferences(rawResponse, retrievalResults);
        if (!finalResponse.equals(rawResponse)
                && finalResponse.startsWith(rawResponse)
                && chunkHandler != null) {
            chunkHandler.accept(finalResponse.substring(rawResponse.length()));
        }
        return finalResponse;
    }

    private RoutingContext prepareRoutingContext(String userId, String userMessage) {
        String rewrittenQuery = queryAnalysisService.rewriteForRouting(userMessage);
        if (isBlankString(rewrittenQuery)) {
            rewrittenQuery = userMessage;
        }

        List<String> subqueries = queryDecomposer.decompose(rewrittenQuery);
        if (subqueries == null || subqueries.isEmpty()) {
            subqueries = List.of(rewrittenQuery);
        }

        List<SubQueryIntent> resolvedSubQueries = intentResolutionService.resolve(userId, subqueries);
        if (resolvedSubQueries == null || resolvedSubQueries.isEmpty()) {
            resolvedSubQueries = List.of(new SubQueryIntent(rewrittenQuery, List.of(defaultRagIntent())));
        }
        return new RoutingContext(rewrittenQuery, resolvedSubQueries);
    }

    private void recordHighConfidencePattern(IntentDecision intent, String query) {
        if (intent == null || query == null || query.isBlank()) {
            return;
        }
        if (intent.getConfidence() != null
                && intent.getConfidence() >= 0.9
                && (intent.getAction() == IntentDecision.Action.ROUTE_RAG
                || intent.getAction() == IntentDecision.Action.ROUTE_TOOL)) {
            intentPatternService.recordPattern(intent.getLevel1(), intent.getLevel2(), query, intent.getConfidence());
        }
    }

    private List<RetrievalMatch> deduplicateSources(List<RetrievalMatch> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        Map<String, RetrievalMatch> map = new HashMap<>();
        for (RetrievalMatch source : sources) {
            if (source == null) {
                continue;
            }
            map.putIfAbsent(buildMatchKey(source), source);
        }
        List<RetrievalMatch> deduplicated = new ArrayList<>(map.values());
        deduplicated.sort((a, b) -> Double.compare(
                b.getRelevanceScore() != null ? b.getRelevanceScore() : 0.0,
                a.getRelevanceScore() != null ? a.getRelevanceScore() : 0.0
        ));
        return deduplicated;
    }

    private List<RetrievalMatch> retrieveMatches(String userId,
                                                 String message,
                                                 int topK,
                                                 IntentDecision intent,
                                                 List<IntentDecision> intentCandidates) {
        QueryPlan plan = queryAnalysisService.createPlan(message);
        List<List<RetrievalMatch>> resultSets = new ArrayList<>();

        resultSets.add(retrieveWithFallback(userId, message, topK, intent, intentCandidates));

        int remainingQueries = ragProperties.getFusion().getMaxQueries() - 1;
        if (ragProperties.getHyde().isEnabled()) {
            remainingQueries -= 1;
        }

        if (ragProperties.getRewrite().isEnabled() && remainingQueries > 0) {
            List<String> rewrites = plan.getRewrittenQueries();
            if (rewrites != null && !rewrites.isEmpty()) {
                    int limit = Math.min(remainingQueries, rewrites.size());
                    for (int i = 0; i < limit; i++) {
                    resultSets.add(retrieveWithFallback(userId, rewrites.get(i), topK, intent, intentCandidates));
                }
            }
        }

        if (ragProperties.getHyde().isEnabled()) {
            String hydeAnswer = plan.getHydeAnswer();
            if (hydeAnswer != null && !hydeAnswer.isBlank()) {
                resultSets.add(multiChannelRetrievalEngine.retrieve(userId, hydeAnswer, topK, null));
            }
        }

        if (!ragProperties.getFusion().isEnabled() || resultSets.size() == 1) {
            return postProcessorService.rerank(message, resultSets.get(0), topK);
        }

        List<RetrievalMatch> fused = fuseByRrf(resultSets, topK, ragProperties.getFusion().getRrfK());
        return postProcessorService.rerank(message, fused, topK);
    }

    private int determineRetrievalK(String message) {
        if (message == null || message.isBlank()) {
            return DEFAULT_RETRIEVAL_K;
        }
        int length = message.trim().length();
        int k = DEFAULT_RETRIEVAL_K;
        if (length > 40) {
            k += 3;
        } else if (length > 20) {
            k += 1;
        }
        if (containsMultiIntentHint(message)) {
            k += 2;
        }
        return Math.min(k, MAX_RETRIEVAL_K);
    }

    private boolean containsMultiIntentHint(String message) {
        return message.contains("以及") || message.contains("和") || message.contains("、");
    }

    private List<RetrievalMatch> retrieveWithFallback(String userId,
                                                      String message,
                                                      int topK,
                                                      IntentDecision intent,
                                                      List<IntentDecision> intentCandidates) {
        List<RetrievalMatch> results = multiChannelRetrievalEngine.retrieve(userId, message, topK, intent, intentCandidates);
        if (!isLowQualityForFallback(results)) {
            return results;
        }

        String refinedQuery = normalizeQuery(message);
        if (!refinedQuery.equals(message)) {
            List<RetrievalMatch> refined = multiChannelRetrievalEngine.retrieve(
                    userId,
                    refinedQuery,
                    Math.min(topK * 2, MAX_RETRIEVAL_K),
                    intent,
                    intentCandidates
            );
            if (!isLowQualityForFallback(refined)) {
                return refined;
            }
        }

        return results;
    }

    private List<RetrievalMatch> fuseByRrf(List<List<RetrievalMatch>> resultSets,
                                           int topK,
                                           int rrfK) {
        Map<String, RetrievalMatch> bestMatch = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();

        for (List<RetrievalMatch> set : resultSets) {
            if (set == null) {
                continue;
            }
            for (int i = 0; i < set.size(); i++) {
                RetrievalMatch match = set.get(i);
                if (match == null) {
                    continue;
                }
                String key = buildMatchKey(match);
                double score = 1.0 / (rrfK + i + 1);
                scores.merge(key, score, Double::sum);
                bestMatch.putIfAbsent(key, match);
            }
        }

        List<RetrievalMatch> fused = new ArrayList<>();
        for (Map.Entry<String, RetrievalMatch> entry : bestMatch.entrySet()) {
            RetrievalMatch match = entry.getValue();
            Double score = scores.get(entry.getKey());
            match.setRelevanceScore(score);
            fused.add(match);
        }

        fused.sort((a, b) -> Double.compare(
                b.getRelevanceScore() != null ? b.getRelevanceScore() : 0.0,
                a.getRelevanceScore() != null ? a.getRelevanceScore() : 0.0
        ));

        if (fused.size() > topK) {
            return fused.subList(0, topK);
        }
        return fused;
    }

    private List<RetrievalMatch> retrieveByStrategy(String userId,
                                                    String message,
                                                    int topK,
                                                    IntentDecision intent) {
        IntentDecision.Strategy strategy = intent.getStrategy() != null
                ? intent.getStrategy()
                : IntentDecision.Strategy.HYBRID;

        if (strategy == IntentDecision.Strategy.PRECISION) {
            List<RetrievalMatch> results = retrieverService.retrieveTextOnly(message, topK, userId);
            return postProcessorService.rerank(message, results, topK);
        }

        if (strategy == IntentDecision.Strategy.CLARIFY_ONLY) {
            return List.of();
        }

        IntentDecision retrievalIntent = intent;
        if (retrievalIntent != null && retrievalIntent.getAction() != IntentDecision.Action.ROUTE_RAG) {
            retrievalIntent = null;
        }
        List<IntentDecision> intentCandidates = intentResolutionService.resolveForQuery(userId, message);
        return retrieveMatches(userId, message, topK, retrievalIntent, intentCandidates);
    }

    private IntentDecision selectPrimaryIntent(SubQueryIntent subQueryIntent) {
        if (subQueryIntent != null && subQueryIntent.candidates() != null && !subQueryIntent.candidates().isEmpty()) {
            return subQueryIntent.candidates().get(0);
        }
        return defaultRagIntent();
    }

    private IntentDecision defaultRagIntent() {
        return IntentDecision.builder()
                .action(IntentDecision.Action.ROUTE_RAG)
                .strategy(IntentDecision.Strategy.HYBRID)
                .build();
    }

    private String buildMatchKey(RetrievalMatch match) {
        String md5 = match.getFileMd5() != null ? match.getFileMd5() : "unknown";
        String chunk = match.getChunkId() != null ? match.getChunkId().toString() : "0";
        return md5 + ":" + chunk;
    }

    private boolean isLowQualityForFallback(List<RetrievalMatch> results) {
        if (results == null || results.isEmpty()) {
            return true;
        }
        Double topScore = results.get(0).getRelevanceScore();
        return topScore == null || topScore < MIN_ACCEPTABLE_SCORE;
    }

    private String normalizeQuery(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("[\\t\\n\\r]", " ")
                .replaceAll("[，。！？；、]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<RetrievalMatch> runFallbackRetrieval(String userId,
                                                      String message,
                                                      int topK) {
        RagProperties.Crag config = ragProperties.getCrag();
        int multiplier = config != null ? config.getFallbackMultiplier() : 2;
        int fallbackK = Math.min(topK * Math.max(1, multiplier), MAX_RETRIEVAL_K);
        List<RetrievalMatch> fallback = retrieverService.retrieveTextOnly(
                message,
                fallbackK,
                userId
        );
        return postProcessorService.rerank(message, fallback, topK);
    }

    private String constructReferenceContext(List<RetrievalMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }

        StringBuilder contextBuilder = new StringBuilder();

        for (int i = 0; i < matches.size(); i++) {
            RetrievalMatch match = matches.get(i);
            String textSnippet = truncateText(match.getTextContent(), MAX_REFERENCE_LENGTH);
            String sourceLabel = getSourceLabel(match);

            contextBuilder.append(String.format(
                    "[%d] (%s) %s\n",
                    i + 1,
                    sourceLabel,
                    textSnippet
            ));
        }

        return contextBuilder.toString();
    }

    private String resolvePromptTemplate(IntentDecision intent) {
        if (intent == null) {
            return null;
        }
        if (intent.getPromptTemplate() != null && !intent.getPromptTemplate().isBlank()) {
            return intent.getPromptTemplate();
        }
        return intent.getLevel2();
    }

    private String applyPromptTemplate(String template, String context) {
        if (template == null || template.isBlank()) {
            return context;
        }
        return template + "\n" + context;
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…";
    }

    private String getSourceLabel(RetrievalMatch match) {
        return match.getSourceFileName() != null ?
                match.getSourceFileName() :
                "未知来源";
    }

    private String appendSourceReferences(String response, List<RetrievalMatch> sources) {
        String safeResponse = response == null ? "" : response;
        if (sources == null || sources.isEmpty()) {
            return safeResponse;
        }
        if (safeResponse.contains("参考来源：")) {
            return safeResponse;
        }

        List<RetrievalMatch> deduplicated = deduplicateSources(sources);
        if (deduplicated.isEmpty()) {
            return safeResponse;
        }

        StringBuilder builder = new StringBuilder(safeResponse);
        if (!safeResponse.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append("参考来源：\n");

        int max = Math.min(MAX_SOURCE_REFERENCE_COUNT, deduplicated.size());
        for (int i = 0; i < max; i++) {
            RetrievalMatch source = deduplicated.get(i);
            builder.append("[")
                    .append(i + 1)
                    .append("] ")
                    .append(getSourceLabel(source));
            if (source.getChunkId() != null) {
                builder.append("（片段#").append(source.getChunkId()).append("）");
            }
            builder.append("\n");
        }

        return builder.toString();
    }

    private void persistTraceMetric(String sessionId,
                                    Long messageId,
                                    String userId,
                                    String query,
                                    long rewriteLatencyMs,
                                    StreamExecutionResult result) {
        if (messageId == null || result == null) {
            return;
        }
        try {
            ChatTraceMetricDO metric = ChatTraceMetricDO.builder()
                    .sessionId(sessionId)
                    .messageId(messageId)
                    .userId(isBlankString(userId) ? DEFAULT_USER_ID : userId.trim())
                    .queryText(query)
                    .rewriteLatencyMs(Math.max(0, rewriteLatencyMs))
                    .retrievalHitRate(computeRetrievalHitRate(result.retrievedCount(), result.retrievalTopK()))
                    .citationRate(computeCitationRate(result.response(), result.sources()))
                    .clarifyTriggered(result.clarifyTriggered() ? 1 : 0)
                    .build();
            chatTraceMetricMapper.insert(metric);
        } catch (Exception e) {
            log.debug("写入在线链路指标失败, messageId={}, error={}", messageId, e.getMessage());
        }
    }

    private double computeRetrievalHitRate(int retrievedCount, int retrievalTopK) {
        if (retrievalTopK <= 0) {
            return 0.0;
        }
        double rate = retrievedCount * 1.0 / retrievalTopK;
        return Math.max(0.0, Math.min(1.0, rate));
    }

    private double computeCitationRate(String answer, List<RetrievalMatch> sources) {
        if (answer == null || answer.isBlank() || sources == null || sources.isEmpty()) {
            return 0.0;
        }
        Matcher matcher = CITATION_INDEX_PATTERN.matcher(answer);
        Set<Integer> cited = new LinkedHashSet<>();
        int maxIndex = Math.min(MAX_SOURCE_REFERENCE_COUNT, sources.size());
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

    private long nanosToMillis(long nanos) {
        return Math.max(0, nanos / 1_000_000L);
    }

    private double round4(double value) {
        return Math.round(value * 10000.0D) / 10000.0D;
    }

    private boolean isBlankString(String str) {
        return str == null || str.isBlank();
    }

}
