package org.buaa.rag.service.impl;

import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.MESSAGE_EMPTY;
import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.MESSAGE_ID_REQUIRED;
import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.SCORE_OUT_OF_RANGE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.core.online.intent.IntentPatternService;
import org.buaa.rag.core.online.intent.IntentResolutionService;
import org.buaa.rag.core.online.intent.IntentRouterService;
import org.buaa.rag.core.online.intent.SubQueryIntent;
import org.buaa.rag.core.online.cache.SemanticCacheService;
import org.buaa.rag.core.online.query.QueryAnalysisService;
import org.buaa.rag.core.online.query.QueryDecomposer;
import org.buaa.rag.core.online.tool.ToolService;
import org.buaa.rag.core.online.validation.AnswerValidator;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.core.model.CragDecision;
import org.buaa.rag.core.model.FeedbackRequest;
import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.QueryPlan;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.dto.resp.ChatRespDTO;
import org.buaa.rag.service.ChatService;
import org.buaa.rag.service.ConversationService;
import org.buaa.rag.core.online.retrieval.postprocessor.RetrievalPostProcessorService;
import org.buaa.rag.service.SmartRetrieverService;
import org.buaa.rag.core.online.retrieval.MultiChannelRetrievalEngine;
import org.buaa.rag.tool.LlmChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private static final String DEFAULT_USER_ID = "anonymous";
    private static final int MAX_REFERENCE_LENGTH = 300;
    private static final int DEFAULT_RETRIEVAL_K = 5;
    private static final int MAX_RETRIEVAL_K = 10;
    private static final double MIN_ACCEPTABLE_SCORE = 0.25;
    private static final String MULTI_INTENT_SYNTHESIS_PROMPT = PromptTemplateLoader.load("chat-multi-intent-synthesis.st");

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
    private final AnswerValidator answerValidator;
    private final RagProperties ragProperties;
    private final SemanticCacheService semanticCacheService;
    private final ConversationService conversationService;

    private record ExecutionResult(String response, List<RetrievalMatch> sources) {
    }

    private record SubQueryExecution(String query,
                                     IntentDecision decision,
                                     String answer,
                                     List<RetrievalMatch> sources) {
    }

    private record RoutingContext(String rewrittenQuery, List<SubQueryIntent> subQueryIntents) {
    }

    @Override
    public Result<Map<String, Object>> handleChatRequest(Map<String, String> payload) {
        String userMessage = payload == null ? null : payload.get("message");
        String userId = payload == null ? DEFAULT_USER_ID : payload.getOrDefault("userId", DEFAULT_USER_ID);

        if (isBlankString(userMessage)) {
            throw new ClientException(MESSAGE_EMPTY);
        }

        ChatRespDTO aiResponse = handleMessage(userId, userMessage);
        return Results.success(Map.of(
                "response", aiResponse.getResponse(),
                "sources", aiResponse.getSources()
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
        handleMessageStreamInternal(
                resolvedUserId,
                message,
                chunk -> {
                    try {
                        emitter.send(chunk);
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
        return Results.success(Map.of("messageId", request.getMessageId(), "score", score));
    }

    private ChatRespDTO handleMessage(String userId, String userMessage) {
        log.info("处理用户消息 - 用户: {}", userId);

        try {
            String sessionId = conversationService.obtainOrCreateSession(userId);
            log.info("会话ID: {}, 用户: {}", sessionId, userId);

            List<Map<String, String>> conversationHistory = conversationService.loadConversationContext(sessionId);
            RoutingContext routingContext = prepareRoutingContext(userId, userMessage);
            List<SubQueryIntent> resolvedSubQueries = routingContext.subQueryIntents();
            String rewrittenQuery = routingContext.rewrittenQuery();
            if (resolvedSubQueries.size() > 1) {
                log.info("检测到多意图问题，子问题数: {}", resolvedSubQueries.size());
                ExecutionResult multiResult = executeMultiIntentRoute(
                        userId,
                        userMessage,
                        resolvedSubQueries,
                        conversationHistory
                );
                Long messageId = conversationService.appendToHistory(
                        sessionId,
                        userId,
                        userMessage,
                        multiResult.response(),
                        multiResult.sources()
                );
                return new ChatRespDTO(multiResult.response(), multiResult.sources(), messageId);
            }

            IntentDecision primaryIntent = resolvedSubQueries.isEmpty()
                    ? defaultRagIntent()
                    : selectPrimaryIntent(resolvedSubQueries.get(0));
            ExecutionResult singleResult = executeIntentRoute(
                    userId,
                    rewrittenQuery,
                    primaryIntent,
                    conversationHistory
            );
            Long messageId = conversationService.appendToHistory(
                    sessionId,
                    userId,
                    userMessage,
                    singleResult.response(),
                    singleResult.sources()
            );
            recordHighConfidencePattern(primaryIntent, rewrittenQuery);

            log.info("消息处理完成 - 用户: {}", userId);
            return new ChatRespDTO(singleResult.response(), singleResult.sources(), messageId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("响应生成被中断", e);
        } catch (Exception e) {
            log.error("消息处理失败: {}", e.getMessage(), e);
            throw new RuntimeException("对话处理异常: " + e.getMessage(), e);
        }
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
            RoutingContext routingContext = prepareRoutingContext(userId, userMessage);
            List<SubQueryIntent> resolvedSubQueries = routingContext.subQueryIntents();
            String rewrittenQuery = routingContext.rewrittenQuery();
            if (resolvedSubQueries.size() > 1) {
                ExecutionResult multiResult = executeMultiIntentRoute(
                        userId,
                        userMessage,
                        resolvedSubQueries,
                        conversationHistory
                );
                conversationService.completeAssistantMessage(
                        sessionId,
                        assistantMessageId,
                        multiResult.response(),
                        multiResult.sources()
                );
                if (chunkHandler != null) {
                    chunkHandler.accept(multiResult.response());
                }
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
            if (primaryIntent.getAction() == IntentDecision.Action.CLARIFY
                    || primaryIntent.getAction() == IntentDecision.Action.ROUTE_TOOL) {
                ExecutionResult immediate = executeIntentRoute(
                        userId,
                        rewrittenQuery,
                        primaryIntent,
                        conversationHistory
                );
                conversationService.completeAssistantMessage(
                        sessionId,
                        assistantMessageId,
                        immediate.response(),
                        immediate.sources()
                );
                if (chunkHandler != null) {
                    chunkHandler.accept(immediate.response());
                }
                if (sourcesHandler != null) {
                    sourcesHandler.accept(immediate.sources());
                }
                if (completionHandler != null) {
                    completionHandler.run();
                }
                return;
            }

            var cacheHit = semanticCacheService.find(rewrittenQuery);
            if (cacheHit.isPresent()) {
                String response = cacheHit.get().response();
                List<RetrievalMatch> cachedSources = cacheHit.get().sources();
                conversationService.completeAssistantMessage(sessionId, assistantMessageId, response, cachedSources);
                if (chunkHandler != null) {
                    chunkHandler.accept(response);
                }
                if (sourcesHandler != null) {
                    sourcesHandler.accept(cachedSources);
                }
                if (completionHandler != null) {
                    completionHandler.run();
                }
                return;
            }

            String promptTemplate = resolvePromptTemplate(primaryIntent);
            int retrievalK = determineRetrievalK(rewrittenQuery);
            List<RetrievalMatch> retrievalResults = retrieveByStrategy(userId, rewrittenQuery, retrievalK, primaryIntent);
            String referenceContext = constructReferenceContext(retrievalResults);
            StringBuilder responseBuilder = new StringBuilder();

            CragDecision decision = postProcessorService.evaluate(rewrittenQuery, retrievalResults);
            if (decision.getAction() == CragDecision.Action.CLARIFY
                    || decision.getAction() == CragDecision.Action.NO_ANSWER) {
                String response = decision.getMessage();
                responseBuilder.append(response);
                if (chunkHandler != null) {
                    chunkHandler.accept(response);
                }
                conversationService.completeAssistantMessage(sessionId, assistantMessageId, response, retrievalResults);
                if (sourcesHandler != null) {
                    sourcesHandler.accept(retrievalResults);
                }
                if (completionHandler != null) {
                    completionHandler.run();
                }
                return;
            }

            if (decision.getAction() == CragDecision.Action.REFINE) {
                List<RetrievalMatch> fallback = runFallbackRetrieval(
                        userId,
                        rewrittenQuery,
                        retrievalK
                );
                if (!fallback.isEmpty()) {
                    retrievalResults = fallback;
                    referenceContext = constructReferenceContext(retrievalResults);
                } else {
                    String response = postProcessorService.noResultMessage();
                    responseBuilder.append(response);
                    if (chunkHandler != null) {
                        chunkHandler.accept(response);
                    }
                    conversationService.completeAssistantMessage(sessionId, assistantMessageId, response, retrievalResults);
                    if (sourcesHandler != null) {
                        sourcesHandler.accept(retrievalResults);
                    }
                    if (completionHandler != null) {
                        completionHandler.run();
                    }
                    return;
                }
            }

            List<RetrievalMatch> finalResults = retrievalResults;
            Long streamMessageId = assistantMessageId;
            String streamSessionId = sessionId;
            java.util.concurrent.atomic.AtomicBoolean streamFailed = new java.util.concurrent.atomic.AtomicBoolean(false);

            llmService.streamResponse(
                    rewrittenQuery,
                    applyPromptTemplate(promptTemplate, referenceContext),
                    conversationHistory,
                    chunk -> {
                        responseBuilder.append(chunk);
                        if (chunkHandler != null) {
                            chunkHandler.accept(chunk);
                        }
                    },
                    error -> {
                        streamFailed.set(true);
                        conversationService.failAssistantMessage(
                                streamSessionId,
                                streamMessageId,
                                "对话服务异常，请稍后重试。"
                        );
                        if (errorHandler != null) {
                            errorHandler.accept(error);
                        }
                    },
                    () -> {
                        if (streamFailed.get()) {
                            if (completionHandler != null) {
                                completionHandler.run();
                            }
                            return;
                        }
                        String finalResponse = responseBuilder.toString();
                        conversationService.completeAssistantMessage(streamSessionId, streamMessageId, finalResponse, finalResults);
                        semanticCacheService.put(rewrittenQuery, finalResponse, finalResults);
                        recordHighConfidencePattern(primaryIntent, rewrittenQuery);
                        if (sourcesHandler != null) {
                            sourcesHandler.accept(finalResults);
                        }
                        if (completionHandler != null) {
                            completionHandler.run();
                        }
                    }
            );
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

    private ExecutionResult executeIntentRoute(String userId,
                                               String query,
                                               IntentDecision intent,
                                               List<Map<String, String>> conversationHistory) throws InterruptedException {
        if (intent == null || intent.getAction() == null) {
            return executeRagRoute(userId, query, IntentDecision.builder()
                    .action(IntentDecision.Action.ROUTE_RAG)
                    .strategy(IntentDecision.Strategy.HYBRID)
                    .build(), conversationHistory);
        }

        if (intent.getAction() == IntentDecision.Action.CLARIFY) {
            String response = intent.getClarifyQuestion() != null
                    ? intent.getClarifyQuestion()
                    : "需要更多信息，请具体描述。";
            return new ExecutionResult(response, List.of());
        }

        if (intent.getAction() == IntentDecision.Action.ROUTE_TOOL) {
            String response = toolService.execute(userId, query, intent);
            return new ExecutionResult(response, List.of());
        }

        return executeRagRoute(userId, query, intent, conversationHistory);
    }

    private ExecutionResult executeRagRoute(String userId,
                                            String query,
                                            IntentDecision intent,
                                            List<Map<String, String>> conversationHistory) throws InterruptedException {
        var cacheHit = semanticCacheService.find(query);
        if (cacheHit.isPresent()) {
            return new ExecutionResult(cacheHit.get().response(), cacheHit.get().sources());
        }

        String promptTemplate = resolvePromptTemplate(intent);
        int retrievalK = determineRetrievalK(query);
        List<RetrievalMatch> retrievalResults = retrieveByStrategy(userId, query, retrievalK, intent);

        CragDecision decision = postProcessorService.evaluate(query, retrievalResults);
        if (decision.getAction() == CragDecision.Action.CLARIFY
                || decision.getAction() == CragDecision.Action.NO_ANSWER) {
            return new ExecutionResult(decision.getMessage(), retrievalResults);
        }

        if (decision.getAction() == CragDecision.Action.REFINE) {
            List<RetrievalMatch> fallback = runFallbackRetrieval(userId, query, retrievalK);
            if (!fallback.isEmpty()) {
                retrievalResults = fallback;
            } else {
                return new ExecutionResult(postProcessorService.noResultMessage(), retrievalResults);
            }
        }

        String referenceContext = constructReferenceContext(retrievalResults);
        String response = generateAnswerBlocking(
                query,
                applyPromptTemplate(promptTemplate, referenceContext),
                conversationHistory
        );

        if (answerValidator.evaluate(query, response) == AnswerValidator.Verdict.REFINE) {
            response = response + "\n（提示：资料可能不足，如需更精确请补充关键信息）";
        }

        semanticCacheService.put(query, response, retrievalResults);
        return new ExecutionResult(response, retrievalResults);
    }

    private ExecutionResult executeMultiIntentRoute(String userId,
                                                    String originalQuery,
                                                    List<SubQueryIntent> resolvedSubQueries,
                                                    List<Map<String, String>> conversationHistory) throws InterruptedException {
        List<SubQueryExecution> executions = new ArrayList<>();
        List<RetrievalMatch> mergedSources = new ArrayList<>();

        for (SubQueryIntent subQueryIntent : resolvedSubQueries) {
            if (subQueryIntent == null || subQueryIntent.subQuery() == null || subQueryIntent.subQuery().isBlank()) {
                continue;
            }
            IntentDecision primaryIntent = selectPrimaryIntent(subQueryIntent);
            ExecutionResult subResult = executeIntentRoute(userId, subQueryIntent.subQuery(), primaryIntent, conversationHistory);
            executions.add(new SubQueryExecution(subQueryIntent.subQuery(), primaryIntent, subResult.response(), subResult.sources()));
            mergedSources.addAll(subResult.sources());
            recordHighConfidencePattern(primaryIntent, subQueryIntent.subQuery());
        }

        if (executions.isEmpty()) {
            IntentDecision fallbackIntent = defaultRagIntent();
            return executeIntentRoute(userId, originalQuery, fallbackIntent, conversationHistory);
        }

        List<RetrievalMatch> deduplicated = deduplicateSources(mergedSources);
        String finalResponse = synthesizeMultiIntentAnswer(originalQuery, executions);
        if (answerValidator.evaluate(originalQuery, finalResponse) == AnswerValidator.Verdict.REFINE) {
            finalResponse = finalResponse + "\n（提示：部分子问题信息不足，如需更精确请补充细节）";
        }
        semanticCacheService.put(originalQuery, finalResponse, deduplicated);
        return new ExecutionResult(finalResponse, deduplicated);
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

    private String synthesizeMultiIntentAnswer(String originalQuery, List<SubQueryExecution> executions) {
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("原始问题：").append(originalQuery).append("\n\n");
        userPrompt.append("子问题处理结果：\n");
        for (int i = 0; i < executions.size(); i++) {
            SubQueryExecution execution = executions.get(i);
            userPrompt.append("[").append(i + 1).append("] 子问题：").append(execution.query()).append("\n");
            userPrompt.append("路由动作：").append(renderAction(execution.decision())).append("\n");
            userPrompt.append("子结论：").append(execution.answer()).append("\n");
            String sourceSummary = summarizeSources(execution.sources(), 2);
            if (!sourceSummary.isBlank()) {
                userPrompt.append("证据：").append(sourceSummary).append("\n");
            }
            userPrompt.append("\n");
        }

        String synthesized = llmService.generateCompletion(
                MULTI_INTENT_SYNTHESIS_PROMPT,
                userPrompt.toString(),
                768
        );
        if (synthesized != null && !synthesized.isBlank()) {
            return synthesized;
        }
        return buildFallbackSynthesis(executions);
    }

    private String buildFallbackSynthesis(List<SubQueryExecution> executions) {
        StringBuilder builder = new StringBuilder("我将你的问题拆分处理如下：\n");
        for (int i = 0; i < executions.size(); i++) {
            SubQueryExecution execution = executions.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(execution.query())
                    .append(" -> ")
                    .append(execution.answer())
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private String summarizeSources(List<RetrievalMatch> sources, int limit) {
        if (sources == null || sources.isEmpty() || limit <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int max = Math.min(limit, sources.size());
        for (int i = 0; i < max; i++) {
            RetrievalMatch source = sources.get(i);
            if (i > 0) {
                builder.append("；");
            }
            builder.append("[")
                    .append(i + 1)
                    .append("] ")
                    .append(getSourceLabel(source))
                    .append(": ")
                    .append(truncateText(source.getTextContent(), 80));
        }
        return builder.toString();
    }

    private String renderAction(IntentDecision decision) {
        if (decision == null || decision.getAction() == null) {
            return "UNKNOWN";
        }
        return switch (decision.getAction()) {
            case ROUTE_RAG -> "ROUTE_RAG";
            case ROUTE_TOOL -> "ROUTE_TOOL";
            case CLARIFY -> "CLARIFY";
        };
    }

    private String generateAnswerBlocking(String userQuery,
                                          String referenceContext,
                                          List<Map<String, String>> conversationHistory) throws InterruptedException {
        StringBuilder responseBuilder = new StringBuilder();
        CountDownLatch completionLatch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        llmService.streamResponse(
                userQuery,
                referenceContext,
                conversationHistory,
                responseBuilder::append,
                error -> {
                    log.error("LLM服务错误: {}", error.getMessage(), error);
                    errorRef.set(error);
                    completionLatch.countDown();
                },
                completionLatch::countDown
        );

        if (!completionLatch.await(120, TimeUnit.SECONDS)) {
            throw new RuntimeException("AI响应超时，请稍后重试");
        }
        if (errorRef.get() != null) {
            throw new RuntimeException("AI服务异常: " + errorRef.get().getMessage(), errorRef.get());
        }
        return responseBuilder.toString();
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

    private boolean isBlankString(String str) {
        return str == null || str.isBlank();
    }

}
