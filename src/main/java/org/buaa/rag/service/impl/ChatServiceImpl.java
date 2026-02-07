package org.buaa.rag.service.impl;

import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.MESSAGE_EMPTY;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.MESSAGE_ID_REQUIRED;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.SCORE_OUT_OF_RANGE;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.config.RagConfiguration;
import org.buaa.rag.dao.entity.MessageDO;
import org.buaa.rag.dao.entity.MessageSourceDO;
import org.buaa.rag.dao.mapper.MessageMapper;
import org.buaa.rag.dao.mapper.MessageSourceMapper;
import org.buaa.rag.dto.resp.ChatRespDTO;
import org.buaa.rag.dto.CragDecision;
import org.buaa.rag.dto.FeedbackRequest;
import org.buaa.rag.dto.MetadataFilter;
import org.buaa.rag.dto.QueryPlan;
import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.service.ChatService;
import org.buaa.rag.service.QueryAnalysisService;
import org.buaa.rag.service.RetrievalPostProcessorService;
import org.buaa.rag.service.SmartRetrieverService;
import org.buaa.rag.tool.LlmChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private static final String DEFAULT_USER_ID = "anonymous";
    private static final int MAX_HISTORY_SIZE = 20;
    private static final int MAX_REFERENCE_LENGTH = 300;
    private static final int DEFAULT_RETRIEVAL_K = 5;
    private static final int MAX_RETRIEVAL_K = 10;
    private static final double MIN_ACCEPTABLE_SCORE = 0.25;

    // 用户ID到会话ID的映射
    private final Map<String, String> userSessionMap = new ConcurrentHashMap<>();
    // 会话历史存储
    private final Map<String, List<Map<String, String>>> sessionHistoryMap = new ConcurrentHashMap<>();

    @Autowired
    private SmartRetrieverService retrieverService;

    @Autowired
    private RetrievalPostProcessorService postProcessorService;

    @Autowired
    private LlmChat llmService;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private MessageSourceMapper sourceRepository;

    @Autowired
    private QueryAnalysisService queryAnalysisService;

    @Autowired
    private RagConfiguration ragConfiguration;

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
                                                            String userId,
                                                            String department,
                                                            String docType,
                                                            String policyYear,
                                                            String tags) {

        MetadataFilter filter = buildMetadataFilter(department, docType, policyYear, tags);
        List<RetrievalMatch> results = retrieverService.retrieve(query, topK, userId, filter);
        results = postProcessorService.rerank(query, results, topK);
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
            // 步骤1: 获取或创建会话
            String sessionId = obtainOrCreateSession(userId);
            log.info("会话ID: {}, 用户: {}", sessionId, userId);

            // 步骤2: 加载对话历史
            List<Map<String, String>> conversationHistory = loadConversationHistory(sessionId);
            log.debug("历史记录数: {}", conversationHistory.size());

            // 步骤3: 执行知识检索
            int retrievalK = determineRetrievalK(userMessage);
            MetadataFilter filter = queryAnalysisService.resolveFilter(userMessage);
            List<RetrievalMatch> retrievalResults = retrieveMatches(userId, userMessage, retrievalK, filter);
            log.debug("检索到 {} 条相关结果", retrievalResults.size());

            CragDecision decision = postProcessorService.evaluate(userMessage, retrievalResults);
            if (decision.getAction() == CragDecision.Action.CLARIFY
                || decision.getAction() == CragDecision.Action.NO_ANSWER) {
                String response = decision.getMessage();
                Long messageId = appendToHistory(sessionId, userId, userMessage, response, retrievalResults);
                return new ChatRespDTO(response, retrievalResults, messageId);
            }

            if (decision.getAction() == CragDecision.Action.REFINE) {
                List<RetrievalMatch> fallback = runFallbackRetrieval(
                    userId,
                    userMessage,
                    retrievalK,
                    filter
                );
                if (!fallback.isEmpty()) {
                    retrievalResults = fallback;
                } else {
                    String response = postProcessorService.noResultMessage();
                    Long messageId = appendToHistory(sessionId, userId, userMessage, response, retrievalResults);
                    return new ChatRespDTO(response, retrievalResults, messageId);
                }
            }

            // 步骤4: 构造参考上下文
            String referenceContext = constructReferenceContext(retrievalResults);

            // 步骤5: 调用LLM生成响应
            StringBuilder responseBuilder = new StringBuilder();
            CountDownLatch completionLatch = new CountDownLatch(1);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();

            log.info("调用LLM生成响应");

            llmService.streamResponse(
                userMessage,
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

            String finalResponse = responseBuilder.toString();
            log.info("LLM响应完成，长度: {}", finalResponse.length());

            // 步骤6: 更新对话历史
            Long messageId = appendToHistory(sessionId, userId, userMessage, finalResponse, retrievalResults);

            log.info("消息处理完成 - 用户: {}", userId);
            return new ChatRespDTO(finalResponse, retrievalResults, messageId);

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
        try {
            String sessionId = obtainOrCreateSession(userId);
            List<Map<String, String>> conversationHistory = loadConversationHistory(sessionId);
            int retrievalK = determineRetrievalK(userMessage);
            MetadataFilter filter = queryAnalysisService.resolveFilter(userMessage);
            List<RetrievalMatch> retrievalResults = retrieveMatches(userId, userMessage, retrievalK, filter);
            String referenceContext = constructReferenceContext(retrievalResults);
            StringBuilder responseBuilder = new StringBuilder();

            CragDecision decision = postProcessorService.evaluate(userMessage, retrievalResults);
            if (decision.getAction() == CragDecision.Action.CLARIFY
                || decision.getAction() == CragDecision.Action.NO_ANSWER) {
                String response = decision.getMessage();
                responseBuilder.append(response);
                if (chunkHandler != null) {
                    chunkHandler.accept(response);
                }
                Long messageId = appendToHistory(sessionId, userId, userMessage, response, retrievalResults);
                if (sourcesHandler != null) {
                    sourcesHandler.accept(retrievalResults);
                }
                if (messageIdHandler != null) {
                    messageIdHandler.accept(messageId);
                }
                if (completionHandler != null) {
                    completionHandler.run();
                }
                return;
            }

            if (decision.getAction() == CragDecision.Action.REFINE) {
                List<RetrievalMatch> fallback = runFallbackRetrieval(
                    userId,
                    userMessage,
                    retrievalK,
                    filter
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
                    Long messageId = appendToHistory(sessionId, userId, userMessage, response, retrievalResults);
                    if (sourcesHandler != null) {
                        sourcesHandler.accept(retrievalResults);
                    }
                    if (messageIdHandler != null) {
                        messageIdHandler.accept(messageId);
                    }
                    if (completionHandler != null) {
                        completionHandler.run();
                    }
                    return;
                }
            }

            List<RetrievalMatch> finalResults = retrievalResults;

            llmService.streamResponse(
                userMessage,
                referenceContext,
                conversationHistory,
                chunk -> {
                    responseBuilder.append(chunk);
                    if (chunkHandler != null) {
                        chunkHandler.accept(chunk);
                    }
                },
                error -> {
                    if (errorHandler != null) {
                        errorHandler.accept(error);
                    }
                },
                () -> {
                    String finalResponse = responseBuilder.toString();
                    Long messageId = appendToHistory(sessionId, userId, userMessage, finalResponse, finalResults);
                    if (sourcesHandler != null) {
                        sourcesHandler.accept(finalResults);
                    }
                    if (messageIdHandler != null) {
                        messageIdHandler.accept(messageId);
                    }
                    if (completionHandler != null) {
                        completionHandler.run();
                    }
                }
            );
        } catch (Exception e) {
            if (errorHandler != null) {
                errorHandler.accept(e);
            }
        }
    }

    private String obtainOrCreateSession(String userId) {
        return userSessionMap.computeIfAbsent(userId, key -> {
            String existingSession = loadLatestSessionId(userId);
            if (existingSession != null) {
                log.info("复用历史会话 - 用户: {}, 会话ID: {}", userId, existingSession);
                return existingSession;
            }

            String newSessionId = UUID.randomUUID().toString();
            log.info("创建新会话 - 用户: {}, 会话ID: {}", userId, newSessionId);
            return newSessionId;
        });
    }

    private List<Map<String, String>> loadConversationHistory(String sessionId) {
        List<Map<String, String>> history = loadHistoryFromDatabase(sessionId);
        if (history != null) {
            sessionHistoryMap.put(sessionId, history);
            return history;
        }
        return sessionHistoryMap.getOrDefault(sessionId, new ArrayList<>());
    }

    private Long appendToHistory(String sessionId,
                                 String userId,
                                 String userMessage,
                                 String aiResponse,
                                 List<RetrievalMatch> sources) {
        List<Map<String, String>> history = sessionHistoryMap.computeIfAbsent(
            sessionId,
            key -> new ArrayList<>()
        );

        String timestamp = getCurrentTimestamp();

        Map<String, String> userEntry = new HashMap<>();
        userEntry.put("role", "user");
        userEntry.put("content", userMessage);
        userEntry.put("timestamp", timestamp);
        history.add(userEntry);
        persistMessage(sessionId, userId, "user", userMessage);

        Map<String, String> assistantEntry = new HashMap<>();
        assistantEntry.put("role", "assistant");
        assistantEntry.put("content", aiResponse);
        assistantEntry.put("timestamp", timestamp);
        history.add(assistantEntry);
        Long assistantMessageId = persistMessage(sessionId, userId, "assistant", aiResponse);
        persistSources(assistantMessageId, sources);

        if (history.size() > MAX_HISTORY_SIZE) {
            List<Map<String, String>> trimmedHistory = new ArrayList<>(
                history.subList(history.size() - MAX_HISTORY_SIZE, history.size())
            );
            sessionHistoryMap.put(sessionId, trimmedHistory);
        }

        log.debug("更新会话历史 - 会话: {}, 总消息数: {}", sessionId, history.size());
        return assistantMessageId;
    }

    private String getCurrentTimestamp() {
        return LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    private List<RetrievalMatch> retrieveMatches(String userId,
                                                 String message,
                                                 int topK,
                                                 MetadataFilter filter) {
        QueryPlan plan = queryAnalysisService.createPlan(message);
        List<List<RetrievalMatch>> resultSets = new ArrayList<>();

        resultSets.add(retrieveWithFallback(userId, message, topK, filter));

        int remainingQueries = ragConfiguration.getFusion().getMaxQueries() - 1;
        if (ragConfiguration.getHyde().isEnabled()) {
            remainingQueries -= 1;
        }

        if (ragConfiguration.getRewrite().isEnabled() && remainingQueries > 0) {
            List<String> rewrites = plan.getRewrittenQueries();
            if (rewrites != null && !rewrites.isEmpty()) {
                int limit = Math.min(remainingQueries, rewrites.size());
                for (int i = 0; i < limit; i++) {
                    resultSets.add(retrieveWithFallback(userId, rewrites.get(i), topK, filter));
                }
            }
        }

        if (ragConfiguration.getHyde().isEnabled()) {
            String hydeAnswer = plan.getHydeAnswer();
            if (hydeAnswer != null && !hydeAnswer.isBlank()) {
                resultSets.add(retrieverService.retrieveVectorOnly(hydeAnswer, topK, userId, filter));
            }
        }

        if (!ragConfiguration.getFusion().isEnabled() || resultSets.size() == 1) {
            return postProcessorService.rerank(message, resultSets.get(0), topK);
        }

        List<RetrievalMatch> fused = fuseByRrf(resultSets, topK, ragConfiguration.getFusion().getRrfK());
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
                                                      MetadataFilter filter) {
        List<RetrievalMatch> results = retrieverService.retrieve(message, topK, userId, filter);
        if (!isLowQualityForFallback(results)) {
            return results;
        }

        String refinedQuery = normalizeQuery(message);
        if (!refinedQuery.equals(message)) {
            List<RetrievalMatch> refined = retrieverService.retrieve(
                refinedQuery,
                Math.min(topK * 2, MAX_RETRIEVAL_K),
                userId,
                filter
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
                                                      int topK,
                                                      MetadataFilter filter) {
        RagConfiguration.Crag config = ragConfiguration.getCrag();
        int multiplier = config != null ? config.getFallbackMultiplier() : 2;
        int fallbackK = Math.min(topK * Math.max(1, multiplier), MAX_RETRIEVAL_K);
        List<RetrievalMatch> fallback = retrieverService.retrieveTextOnly(
            message,
            fallbackK,
            userId,
            filter
        );
        return postProcessorService.rerank(message, fallback, topK);
    }

    private String loadLatestSessionId(String userId) {
        try {
            return messageMapper.findTop1ByUserIdOrderByCreatedAtDesc(userId)
                .map(MessageDO::getSessionId)
                .orElse(null);
        } catch (Exception e) {
            log.debug("读取历史会话失败: {}", e.getMessage());
            return null;
        }
    }

    private List<Map<String, String>> loadHistoryFromDatabase(String sessionId) {
        try {
            List<MessageDO> messageDOS = messageMapper
                .findTop20BySessionIdOrderByCreatedAtAsc(sessionId);
            if (messageDOS == null || messageDOS.isEmpty()) {
                return null;
            }
            List<Map<String, String>> history = new ArrayList<>();
            for (MessageDO messageDO : messageDOS) {
                Map<String, String> entry = new HashMap<>();
                entry.put("role", messageDO.getRole());
                entry.put("content", messageDO.getContent());
                entry.put("timestamp", formatTimestamp(messageDO.getCreatedAt()));
                history.add(entry);
            }
            return history;
        } catch (Exception e) {
            log.debug("加载对话历史失败: {}", e.getMessage());
            return null;
        }
    }

    private Long persistMessage(String sessionId, String userId, String role, String content) {
        try {
            MessageDO messageDO = new MessageDO();
            messageDO.setSessionId(sessionId);
            messageDO.setUserId(userId);
            messageDO.setRole(role);
            messageDO.setContent(content);
            long id = messageMapper.insert(messageDO);
            return id;
        } catch (Exception e) {
            log.debug("持久化对话失败: {}", e.getMessage());
            return null;
        }
    }

    private void persistSources(Long messageId, List<RetrievalMatch> sources) {
        if (messageId == null || sources == null || sources.isEmpty()) {
            return;
        }
        try {
            for (RetrievalMatch match : sources) {
                MessageSourceDO source = new MessageSourceDO();
                source.setMessageId(messageId);
                source.setDocumentMd5(match.getFileMd5());
                source.setChunkId(match.getChunkId());
                source.setRelevanceScore(match.getRelevanceScore());
                source.setSourceFileName(match.getSourceFileName());
                sourceRepository.insert(source);
            }
        } catch (Exception e) {
            log.debug("持久化来源失败: {}", e.getMessage());
        }
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return getCurrentTimestamp();
        }
        return timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
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

    private MetadataFilter buildMetadataFilter(String department,
                                               String docType,
                                               String policyYear,
                                               String tags) {
        MetadataFilter filter = new MetadataFilter();
        if (department != null && !department.isBlank()) {
            filter.setDepartment(department.trim());
        }
        if (docType != null && !docType.isBlank()) {
            filter.setDocType(docType.trim());
        }
        if (policyYear != null && !policyYear.isBlank()) {
            filter.setPolicyYear(policyYear.trim());
        }
        List<String> tagList = parseTags(tags);
        if (!tagList.isEmpty()) {
            filter.setTags(tagList);
        }
        return filter;
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = tags.split("[,，;；]");
        List<String> results = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                results.add(trimmed);
            }
        }
        return results.stream().distinct().toList();
    }
}
