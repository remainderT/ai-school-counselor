package org.buaa.rag.core.online.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.buaa.rag.core.model.ChatRoutingPlan;
import org.buaa.rag.core.model.CragDecision;
import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.ChatExecutionResult;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.cache.SemanticCacheService;
import org.buaa.rag.core.online.intent.IntentPatternService;
import org.buaa.rag.core.online.intent.IntentResolutionService;
import org.buaa.rag.core.online.intent.SubQueryIntent;
import org.buaa.rag.core.online.retrieval.SubQueryRetrievalResult;
import org.buaa.rag.core.online.retrieval.SubQueryRetrievalService;
import org.buaa.rag.core.online.retrieval.postprocessor.RetrievalPostProcessorService;
import org.buaa.rag.core.online.rewrite.QueryRewriteAndSplitService;
import org.buaa.rag.core.online.rewrite.QueryRewriteResult;
import org.buaa.rag.core.online.tool.ToolService;
import org.buaa.rag.properties.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class OnlineChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OnlineChatOrchestrator.class);

    private final QueryRewriteAndSplitService queryRewriteAndSplitService;
    private final IntentResolutionService intentResolutionService;
    private final SubQueryRetrievalService subQueryRetrievalService;
    private final RetrievalPostProcessorService postProcessorService;
    private final RagPromptService ragPromptService;
    private final ToolService toolService;
    private final IntentPatternService intentPatternService;
    private final RagProperties ragProperties;
    private final SemanticCacheService semanticCacheService;
    private final Executor subQueryContextExecutor;
    private final Executor chatStreamExecutor;

    public OnlineChatOrchestrator(QueryRewriteAndSplitService queryRewriteAndSplitService,
                                  IntentResolutionService intentResolutionService,
                                  SubQueryRetrievalService subQueryRetrievalService,
                                  RetrievalPostProcessorService postProcessorService,
                                  RagPromptService ragPromptService,
                                  ToolService toolService,
                                  IntentPatternService intentPatternService,
                                  RagProperties ragProperties,
                                  SemanticCacheService semanticCacheService,
                                  @Qualifier("subQueryContextExecutor") Executor subQueryContextExecutor,
                                  @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.queryRewriteAndSplitService = queryRewriteAndSplitService;
        this.intentResolutionService = intentResolutionService;
        this.subQueryRetrievalService = subQueryRetrievalService;
        this.postProcessorService = postProcessorService;
        this.ragPromptService = ragPromptService;
        this.toolService = toolService;
        this.intentPatternService = intentPatternService;
        this.ragProperties = ragProperties;
        this.semanticCacheService = semanticCacheService;
        this.subQueryContextExecutor = subQueryContextExecutor;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    // ──────────────────────── public API ────────────────────────

    /**
     * 不带历史的入口（兼容旧调用）。
     */
    public ChatRoutingPlan rewriteSplitAndResolve(String userId, String userMessage) {
        return rewriteSplitAndResolve(userId, userMessage, null);
    }

    /**
     * 携带对话历史的入口：历史用于指代消歧（最近 2 轮）。
     */
    public ChatRoutingPlan rewriteSplitAndResolve(String userId,
                                                   String userMessage,
                                                   List<Map<String, String>> conversationHistory) {
        QueryRewriteResult rewriteResult = queryRewriteAndSplitService.rewriteAndSplit(
                userMessage, conversationHistory);
        String rewrittenQuery = rewriteResult.rewrittenQuery();
        List<String> subQueries = rewriteResult.effectiveSubQuestions();

        List<SubQueryIntent> resolvedSubQueries = intentResolutionService.resolve(userId, subQueries);
        if (resolvedSubQueries == null || resolvedSubQueries.isEmpty()) {
            resolvedSubQueries = List.of(new SubQueryIntent(rewrittenQuery,
                    List.of(subQueryRetrievalService.defaultHybridIntent())));
        }
        return new ChatRoutingPlan(rewrittenQuery, resolvedSubQueries, rewriteResult.latencyMs());
    }

    public String detectGuidanceQuestion(ChatRoutingPlan routingPlan) {
        if (routingPlan == null || routingPlan.subQueryIntents() == null) {
            return null;
        }
        for (SubQueryIntent subQueryIntent : routingPlan.subQueryIntents()) {
            IntentDecision primary = subQueryRetrievalService.selectPrimaryIntent(subQueryIntent);
            if (primary != null
                    && primary.getAction() == IntentDecision.Action.CLARIFY
                    && !isBlank(primary.getClarifyQuestion())) {
                return primary.getClarifyQuestion();
            }
        }
        return null;
    }

    public ChatExecutionResult executeWithPlan(String userId,
                                               String userMessage,
                                               List<Map<String, String>> conversationHistory,
                                               ChatRoutingPlan routingPlan,
                                               Consumer<String> chunkHandler) {
        if (routingPlan == null) {
            routingPlan = rewriteSplitAndResolve(userId, userMessage);
        }

        List<SubQueryIntent> resolvedSubQueries = routingPlan.subQueryIntents();
        String rewrittenQuery = routingPlan.rewrittenQuery();

        StreamResult executionResult;
        if (resolvedSubQueries.size() > 1) {
            executionResult = streamMultiIntentRoute(userId, userMessage, resolvedSubQueries, conversationHistory, chunkHandler);
        } else {
            IntentDecision primaryIntent = resolvedSubQueries.isEmpty()
                    ? subQueryRetrievalService.defaultHybridIntent()
                    : subQueryRetrievalService.selectPrimaryIntent(resolvedSubQueries.get(0));
            List<IntentDecision> candidates = resolvedSubQueries.isEmpty()
                    ? List.of()
                    : resolvedSubQueries.get(0).candidates();
            executionResult = streamIntentRoute(userId, rewrittenQuery, primaryIntent, candidates, conversationHistory, chunkHandler);
            recordHighConfidencePattern(primaryIntent, rewrittenQuery);
        }

        return new ChatExecutionResult(
                executionResult.response(),
                executionResult.sources(),
                executionResult.clarifyTriggered(),
                executionResult.retrievedCount(),
                executionResult.retrievalTopK(),
                routingPlan.rewriteLatencyMs()
        );
    }

    // ──────────────────────── private ────────────────────────

    /**
     * 多子问题并行路由：每个子问题并行执行（RAG检索 or Tool调用），
     * 收集结果后聚合 rerank，统一生成最终回复（分段格式）。
     */
    private StreamResult streamMultiIntentRoute(String userId,
                                                String originalQuery,
                                                List<SubQueryIntent> resolvedSubQueries,
                                                List<Map<String, String>> conversationHistory,
                                                Consumer<String> chunkHandler) {
        List<SubQueryIntent> validSubQueries = resolvedSubQueries == null ? List.of()
                : resolvedSubQueries.stream()
                .filter(item -> item != null && !isBlank(item.subQuery()))
                .toList();

        if (validSubQueries.isEmpty()) {
            return streamIntentRoute(userId, originalQuery, subQueryRetrievalService.defaultHybridIntent(),
                    List.of(), conversationHistory, chunkHandler);
        }

        // 并行执行每个子问题（RAG or Tool）
        List<CompletableFuture<SubQueryRetrievalResult>> futures = validSubQueries.stream()
                .map(subQueryIntent -> CompletableFuture.supplyAsync(
                        () -> executeSubQueryTask(userId, subQueryIntent),
                        subQueryContextExecutor
                ))
                .toList();

        List<SubQueryRetrievalResult> results = futures.stream()
                .map(f -> {
                    try {
                        return f.join();
                    } catch (Exception e) {
                        log.warn("子问题并行执行失败: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(r -> r != null)
                .toList();

        // 分段输出：每个子问题一段
        List<RetrievalMatch> mergedSources = new ArrayList<>();
        StringBuilder mergedResponse = new StringBuilder();
        boolean clarifyTriggered = false;
        int retrievedCount = 0;
        int retrievalTopK = 0;
        int validCount = 0;

        for (int i = 0; i < results.size(); i++) {
            SubQueryRetrievalResult r = results.get(i);
            validCount++;
            String header = (mergedResponse.length() == 0 ? "" : "\n\n")
                    + "【子问题" + validCount + "】" + r.query() + "\n";
            mergedResponse.append(header);
            if (chunkHandler != null) {
                chunkHandler.accept(header);
            }

            retrievedCount += r.retrievedCount();
            retrievalTopK += r.retrievalTopK();
            clarifyTriggered = clarifyTriggered || r.clarifyTriggered();

            if (!isBlank(r.directResponse())) {
                // Tool / Clarify 直接回复
                mergedResponse.append(r.directResponse());
                if (chunkHandler != null) {
                    chunkHandler.accept(r.directResponse());
                }
            } else {
                // RAG 路径：在子问题 sources 上生成回答
                mergedSources.addAll(r.sources());
            }
        }

        if (validCount == 0) {
            return streamIntentRoute(userId, originalQuery, subQueryRetrievalService.defaultHybridIntent(),
                    List.of(), conversationHistory, chunkHandler);
        }

        // 对所有 RAG 子问题的 sources 聚合 rerank，追加来源引用
        List<RetrievalMatch> deduplicated = subQueryRetrievalService.deduplicateAndSort(mergedSources);
        String rawMerged = mergedResponse.toString();
        String finalResponse = ragPromptService.appendSourceReferences(rawMerged, deduplicated);
        if (!finalResponse.equals(rawMerged) && finalResponse.startsWith(rawMerged) && chunkHandler != null) {
            chunkHandler.accept(finalResponse.substring(rawMerged.length()));
        }
        semanticCacheService.put(originalQuery, finalResponse, deduplicated);
        return new StreamResult(finalResponse, deduplicated, clarifyTriggered, retrievedCount, retrievalTopK);
    }

    /**
     * 单子问题路由：SemanticCache → Tool → RAG。
     */
    private StreamResult streamIntentRoute(String userId,
                                           String query,
                                           IntentDecision intent,
                                           List<IntentDecision> preResolvedCandidates,
                                           List<Map<String, String>> conversationHistory,
                                           Consumer<String> chunkHandler) {
        IntentDecision resolved = (intent == null || intent.getAction() == null)
                ? subQueryRetrievalService.defaultHybridIntent()
                : intent;

        if (resolved.getAction() == IntentDecision.Action.CLARIFY) {
            String response = !isBlank(resolved.getClarifyQuestion())
                    ? resolved.getClarifyQuestion()
                    : "需要更多信息，请具体描述。";
            emit(chunkHandler, response);
            return new StreamResult(response, List.of(), true, 0, 0);
        }

        if (resolved.getAction() == IntentDecision.Action.ROUTE_TOOL) {
            String response = toolService.execute(userId, query, resolved);
            emit(chunkHandler, response);
            return new StreamResult(response, List.of(), false, 0, 0);
        }

        // 语义缓存命中
        var cacheHit = semanticCacheService.find(query);
        if (cacheHit.isPresent()) {
            List<RetrievalMatch> cachedSources = cacheHit.get().sources();
            String response = ragPromptService.appendSourceReferences(cacheHit.get().response(), cachedSources);
            emit(chunkHandler, response);
            return new StreamResult(response, cachedSources, false, 0, 0);
        }

        String promptTemplate = ragPromptService.resolvePromptTemplate(resolved);
        int topK = subQueryRetrievalService.determineTopK(query);
        List<RetrievalMatch> retrievalResults = subQueryRetrievalService.retrieveByStrategy(
                userId, query, topK, resolved, preResolvedCandidates);

        CragDecision decision = postProcessorService.evaluate(query, retrievalResults);
        if (decision.getAction() == CragDecision.Action.CLARIFY
                || decision.getAction() == CragDecision.Action.NO_ANSWER) {
            String response = ragPromptService.appendSourceReferences(decision.getMessage(), retrievalResults);
            emit(chunkHandler, response);
            boolean triggered = decision.getAction() == CragDecision.Action.CLARIFY;
            return new StreamResult(response, retrievalResults, triggered, retrievalResults.size(), topK);
        }

        if (decision.getAction() == CragDecision.Action.REFINE) {
            List<RetrievalMatch> fallback = subQueryRetrievalService.fallbackRetrieval(userId, query, topK);
            if (!fallback.isEmpty()) {
                retrievalResults = fallback;
            } else {
                String response = ragPromptService.appendSourceReferences(
                        postProcessorService.noResultMessage(), retrievalResults);
                emit(chunkHandler, response);
                return new StreamResult(response, retrievalResults, false, retrievalResults.size(), topK);
            }
        }

        // 判断当前路由是否包含 Tool 场景（影响 LLM 温度选择）
        boolean hasToolContext = resolved.getAction() == IntentDecision.Action.ROUTE_TOOL;
        String finalResponse = ragPromptService.generateRagAnswer(
                query, promptTemplate, conversationHistory, retrievalResults, chunkHandler, hasToolContext);
        semanticCacheService.put(query, finalResponse, retrievalResults);
        return new StreamResult(finalResponse, retrievalResults, false, retrievalResults.size(), topK);
    }

    /**
     * 单个子问题任务：ROUTE_TOOL 直接调用工具，否则走 RAG 检索。
     */
    private SubQueryRetrievalResult executeSubQueryTask(String userId, SubQueryIntent subQueryIntent) {
        IntentDecision primary = subQueryRetrievalService.selectPrimaryIntent(subQueryIntent);
        recordHighConfidencePattern(primary, subQueryIntent.subQuery());

        if (primary != null && primary.getAction() == IntentDecision.Action.ROUTE_TOOL) {
            String toolResponse = toolService.execute(userId, subQueryIntent.subQuery(), primary);
            return new SubQueryRetrievalResult(
                    subQueryIntent.subQuery(), primary, List.of(),
                    false, null, toolResponse, 0, 0
            );
        }

        if (primary != null && primary.getAction() == IntentDecision.Action.CLARIFY) {
            String clarifyMsg = !isBlank(primary.getClarifyQuestion())
                    ? primary.getClarifyQuestion()
                    : "需要更多信息，请具体描述。";
            return new SubQueryRetrievalResult(
                    subQueryIntent.subQuery(), primary, List.of(),
                    true, clarifyMsg, clarifyMsg, 0, 0
            );
        }

        return subQueryRetrievalService.retrieveForSubQuery(userId, subQueryIntent);
    }

    private void recordHighConfidencePattern(IntentDecision intent, String query) {
        if (intent == null || isBlank(query)) {
            return;
        }
        if (intent.getConfidence() != null
                && intent.getConfidence() >= 0.9
                && (intent.getAction() == IntentDecision.Action.ROUTE_RAG
                || intent.getAction() == IntentDecision.Action.ROUTE_TOOL)) {
            intentPatternService.recordPattern(intent.getLevel1(), intent.getLevel2(), query, intent.getConfidence());
        }
    }

    private void emit(Consumer<String> chunkHandler, String text) {
        if (chunkHandler != null && text != null) {
            chunkHandler.accept(text);
        }
    }

    private boolean isBlank(String str) {
        return str == null || str.isBlank();
    }

    private record StreamResult(String response,
                                List<RetrievalMatch> sources,
                                boolean clarifyTriggered,
                                int retrievedCount,
                                int retrievalTopK) {
    }
}
