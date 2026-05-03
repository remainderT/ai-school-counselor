package org.buaa.rag.core.online.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.buaa.rag.core.model.CragDecision;
import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.intent.IntentPatternService;
import org.buaa.rag.core.online.intent.SubQueryIntent;
import org.buaa.rag.core.online.retrieval.SubQueryRetrievalResult;
import org.buaa.rag.core.online.retrieval.SubQueryRetrievalService;
import org.buaa.rag.core.online.retrieval.postprocessor.RetrievalPostProcessorServiceImpl;
import org.buaa.rag.core.online.tool.ToolService;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.core.trace.RagTraceContext;
import org.buaa.rag.core.trace.RagTraceNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RouteExecutionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RouteExecutionCoordinator.class);
    private static final String CHAT_FALLBACK_PROMPT =
        "你是高校智能助手。对于问候或闲聊请简短友好回应；若用户尚未给出具体诉求，礼貌引导其说明想咨询的事项。";

    private final SubQueryRetrievalService subQueryRetrievalService;
    private final RetrievalPostProcessorServiceImpl postProcessorService;
    private final RagPromptService ragPromptService;
    private final ToolService toolService;
    private final IntentPatternService intentPatternService;
    private final RagProperties ragProperties;
    private final Executor subQueryContextExecutor;
    private final Executor chatStreamExecutor;

    public RouteExecutionCoordinator(SubQueryRetrievalService subQueryRetrievalService,
                                     RetrievalPostProcessorServiceImpl postProcessorService,
                                     RagPromptService ragPromptService,
                                     ToolService toolService,
                                     IntentPatternService intentPatternService,
                                     RagProperties ragProperties,
                                     @Qualifier("subQueryContextExecutor") Executor subQueryContextExecutor,
                                     @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.subQueryRetrievalService = subQueryRetrievalService;
        this.postProcessorService = postProcessorService;
        this.ragPromptService = ragPromptService;
        this.toolService = toolService;
        this.intentPatternService = intentPatternService;
        this.ragProperties = ragProperties;
        this.subQueryContextExecutor = subQueryContextExecutor;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    @RagTraceNode(name = "route-execution", type = "ROUTE_EXECUTE")
    public ExecutionResult execute(String userId,
                                   String userMessage,
                                   List<Map<String, String>> conversationHistory,
                                   String rewrittenQuery,
                                   List<SubQueryIntent> resolvedSubQueries,
                                   long rewriteLatencyMs,
                                   Consumer<String> chunkHandler,
                                   StreamCancellationHandle cancelHandle) {

        StreamResult executionResult;
        if (resolvedSubQueries.size() > 1) {
            executionResult = executeMultiIntentRoute(
                userId, userMessage, resolvedSubQueries, conversationHistory, chunkHandler, cancelHandle);
        } else {
            IntentDecision primaryIntent = resolvedSubQueries.isEmpty()
                ? subQueryRetrievalService.defaultHybridIntent()
                : subQueryRetrievalService.selectPrimaryIntent(resolvedSubQueries.get(0));
            List<IntentDecision> candidates = resolvedSubQueries.isEmpty()
                ? List.of()
                : resolvedSubQueries.get(0).candidates();
            executionResult = executeSingleIntentRoute(
                userId, rewrittenQuery, primaryIntent, candidates, conversationHistory, chunkHandler, cancelHandle);
            recordHighConfidencePattern(primaryIntent, rewrittenQuery);
        }

        return new ExecutionResult(
            executionResult.response(),
            executionResult.sources(),
            executionResult.clarifyTriggered(),
            executionResult.retrievedCount(),
            executionResult.retrievalTopK(),
            rewriteLatencyMs
        );
    }

    private StreamResult executeMultiIntentRoute(String userId,
                                                 String originalQuery,
                                                 List<SubQueryIntent> resolvedSubQueries,
                                                 List<Map<String, String>> conversationHistory,
                                                 Consumer<String> chunkHandler,
                                                 StreamCancellationHandle cancelHandle) {
        long routeStart = System.nanoTime();
        List<SubQueryIntent> validSubQueries = resolvedSubQueries == null ? List.of()
            : resolvedSubQueries.stream()
            .filter(item -> item != null && !isBlank(item.subQuery()))
            .toList();

        if (validSubQueries.isEmpty()) {
            return executeSingleIntentRoute(
                userId, originalQuery, subQueryRetrievalService.defaultHybridIntent(),
                List.of(), conversationHistory, chunkHandler, cancelHandle);
        }

        // 捕获父线程 Trace 上下文，跨线程传播到子任务
        RagTraceContext.Snapshot traceSnapshot = RagTraceContext.capture();

        List<CompletableFuture<SubQueryRetrievalResult>> futures = validSubQueries.stream()
            .map(subQueryIntent -> CompletableFuture.supplyAsync(
                () -> {
                    RagTraceContext.restore(traceSnapshot);
                    try {
                        return executeSubQueryTask(userId, subQueryIntent, conversationHistory);
                    } finally {
                        RagTraceContext.clear();
                    }
                },
                subQueryContextExecutor
            ))
            .toList();

        long subQueryExecutionStart = System.nanoTime();
        List<SubQueryRetrievalResult> results = futures.stream()
            .map(future -> {
                try {
                    return future.join();
                } catch (Exception e) {
                    log.warn("子问题并行执行失败: {}", e.getMessage());
                    return null;
                }
            })
            .filter(item -> item != null)
            .toList();
        long subQueryExecutionElapsed = elapsedMs(subQueryExecutionStart);

        List<RetrievalMatch> mergedSources = new ArrayList<>();
        boolean clarifyTriggered = false;
        int retrievedCount = 0;
        int retrievalTopK = 0;
        boolean allDirect = !results.isEmpty();

        for (SubQueryRetrievalResult result : results) {
            retrievedCount += result.retrievedCount();
            retrievalTopK += result.retrievalTopK();
            clarifyTriggered = clarifyTriggered || result.clarifyTriggered();
            allDirect = allDirect && !isBlank(result.directResponse());
            log.info("子问题执行完成 | query='{}' | intent={} | mode={} | sources={}",
                compact(result.query()), describeIntent(result.intent()),
                isBlank(result.directResponse()) ? "RAG" : "DIRECT",
                result.sources() == null ? 0 : result.sources().size());
            if (result.sources() != null && !result.sources().isEmpty()) {
                mergedSources.addAll(result.sources());
            }
        }

        boolean hasUsableEvidence = results.stream().anyMatch(this::hasUsableEvidence);
        log.info("多意图子问题阶段完成 | query='{}' | 子问题数={} | 子任务耗时={}ms | 可用证据={} | 原始来源数={}",
            compact(originalQuery), results.size(), subQueryExecutionElapsed, hasUsableEvidence, mergedSources.size());

        if (!hasUsableEvidence) {
            return executeSingleIntentRoute(
                userId, originalQuery, subQueryRetrievalService.defaultHybridIntent(),
                List.of(), conversationHistory, chunkHandler, cancelHandle);
        }

        List<RetrievalMatch> deduplicated = ragPromptService.collectDisplayedMultiIntentSources(results);
        FastPathMode fastPathMode = resolveFastPathMode(results, allDirect);
        if (fastPathMode != FastPathMode.NONE) {
            String stitched = stitchFastPathResponse(results, fastPathMode);
            emit(chunkHandler, stitched);
            if (fastPathMode == FastPathMode.STRUCTURED) {
                log.info("多意图结构化直返完成 | query='{}' | 子问题数={} | 去重来源数={} | 总耗时={}ms",
                    compact(originalQuery), results.size(), deduplicated.size(), elapsedMs(routeStart));
                return new StreamResult(stitched, deduplicated, clarifyTriggered, retrievedCount, retrievalTopK);
            }
            if (fastPathMode == FastPathMode.ALL_DIRECT_TEMPLATE) {
                log.info("多意图全直返模板完成 | query='{}' | 子问题数={} | 总耗时={}ms",
                    compact(originalQuery), results.size(), elapsedMs(routeStart));
            } else {
                log.info("多意图直接拼接完成 | query='{}' | 子问题数={} | 总耗时={}ms",
                    compact(originalQuery), results.size(), elapsedMs(routeStart));
            }
            return new StreamResult(stitched, List.of(), clarifyTriggered, retrievedCount, retrievalTopK);
        }

        long synthesizeStart = System.nanoTime();
        String synthesized = ragPromptService.generateMultiIntentAnswer(
            originalQuery, conversationHistory, results, chunkHandler, cancelHandle);
        log.info("多意图答案生成完成 | query='{}' | 子问题数={} | 去重来源数={} | 综合生成耗时={}ms | 总耗时={}ms",
            compact(originalQuery), results.size(), deduplicated.size(), elapsedMs(synthesizeStart), elapsedMs(routeStart));
        return new StreamResult(synthesized, deduplicated, clarifyTriggered, retrievedCount, retrievalTopK);
    }

    private StreamResult executeSingleIntentRoute(String userId,
                                                  String query,
                                                  IntentDecision intent,
                                                  List<IntentDecision> preResolvedCandidates,
                                                  List<Map<String, String>> conversationHistory,
                                                  Consumer<String> chunkHandler,
                                                  StreamCancellationHandle cancelHandle) {
        long routeStart = System.nanoTime();
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
            long toolStart = System.nanoTime();
            String response = toolService.execute(userId, query, resolved);
            emit(chunkHandler, response);
            log.info("单问题工具路由完成 | query='{}' | tool={} | 耗时={}ms",
                compact(query), resolved.getToolName(), elapsedMs(toolStart));
            return new StreamResult(response, List.of(), false, 0, 0);
        }

        if (resolved.getAction() == IntentDecision.Action.ROUTE_CHAT) {
            long chatStart = System.nanoTime();
            String response = generateChatDirectResponse(query, resolved, conversationHistory, chunkHandler);
            log.info("单问题直聊完成 | query='{}' | 耗时={}ms", compact(query), elapsedMs(chatStart));
            return new StreamResult(response, List.of(), false, 0, 0);
        }

        int topK = subQueryRetrievalService.determineTopK(query);
        long retrievalStart = System.nanoTime();
        List<RetrievalMatch> retrievalResults = subQueryRetrievalService.retrieveByStrategy(
            userId, query, topK, resolved, preResolvedCandidates);
        long retrievalElapsed = elapsedMs(retrievalStart);

        long cragStart = System.nanoTime();
        CragDecision decision = postProcessorService.evaluate(query, retrievalResults);
        long cragElapsed = elapsedMs(cragStart);
        if (decision.getAction() == CragDecision.Action.CLARIFY
            || decision.getAction() == CragDecision.Action.NO_ANSWER) {
            String response = decision.getMessage();
            emit(chunkHandler, response);
            boolean triggered = decision.getAction() == CragDecision.Action.CLARIFY;
            log.info("单问题检索结束 | query='{}' | action={} | topK={} | results={} | retrieval={}ms | crag={}ms | 总耗时={}ms",
                compact(query), decision.getAction(), topK, retrievalResults == null ? 0 : retrievalResults.size(),
                retrievalElapsed, cragElapsed, elapsedMs(routeStart));
            return new StreamResult(response, ragPromptService.limitSourcesForAnswer(retrievalResults), triggered, retrievalResults.size(), topK);
        }

        if (decision.getAction() == CragDecision.Action.REFINE) {
            long fallbackStart = System.nanoTime();
            List<RetrievalMatch> fallback = subQueryRetrievalService.fallbackRetrieval(userId, query, topK);
            if (!fallback.isEmpty()) {
                retrievalResults = fallback;
                log.info("单问题检索触发兜底 | query='{}' | fallbackResults={} | fallback耗时={}ms",
                    compact(query), fallback.size(), elapsedMs(fallbackStart));
            } else {
                String response = postProcessorService.noResultMessage();
                emit(chunkHandler, response);
                return new StreamResult(response, ragPromptService.limitSourcesForAnswer(retrievalResults), false, retrievalResults.size(), topK);
            }
        }

        List<RetrievalMatch> displayedSources = ragPromptService.limitSourcesForAnswer(retrievalResults);

        long answerStart = System.nanoTime();
        String finalResponse = ragPromptService.generateSingleIntentStructuredAnswer(
            query,
            conversationHistory,
            displayedSources,
            new RagPromptService.IntentPromptDescriptor(
                resolved.getLevel2(),
                resolved.getPromptTemplate(),
                resolved.getPromptSnippet()
            ),
            chunkHandler,
            false,
            cancelHandle
        );
        log.info("单问题答案生成完成 | query='{}' | topK={} | results={} | retrieval={}ms | crag={}ms | answer={}ms | 总耗时={}ms",
            compact(query), topK, retrievalResults == null ? 0 : retrievalResults.size(),
            retrievalElapsed, cragElapsed, elapsedMs(answerStart), elapsedMs(routeStart));
        return new StreamResult(finalResponse, displayedSources, false, retrievalResults.size(), topK);
    }

    private SubQueryRetrievalResult executeSubQueryTask(String userId,
                                                        SubQueryIntent subQueryIntent,
                                                        List<Map<String, String>> conversationHistory) {
        long start = System.nanoTime();
        IntentDecision primary = subQueryRetrievalService.selectPrimaryIntent(subQueryIntent);
        recordHighConfidencePattern(primary, subQueryIntent.subQuery());

        if (primary != null && primary.getAction() == IntentDecision.Action.ROUTE_TOOL) {
            String toolResponse = toolService.execute(userId, subQueryIntent.subQuery(), primary);
            log.info("子问题工具执行完成 | query='{}' | tool={} | 耗时={}ms",
                compact(subQueryIntent.subQuery()), primary.getToolName(), elapsedMs(start));
            return new SubQueryRetrievalResult(
                subQueryIntent.subQuery(), primary, List.of(),
                false, null, toolResponse, 0, 0
            );
        }

        if (primary != null && primary.getAction() == IntentDecision.Action.CLARIFY) {
            String clarifyMsg = !isBlank(primary.getClarifyQuestion())
                ? primary.getClarifyQuestion()
                : "需要更多信息，请具体描述。";
            log.info("子问题澄清返回 | query='{}' | 耗时={}ms", compact(subQueryIntent.subQuery()), elapsedMs(start));
            return new SubQueryRetrievalResult(
                subQueryIntent.subQuery(), primary, List.of(),
                true, clarifyMsg, clarifyMsg, 0, 0
            );
        }

        if (primary != null && primary.getAction() == IntentDecision.Action.ROUTE_CHAT) {
            String chatResponse = generateChatDirectResponse(
                subQueryIntent.subQuery(), primary, conversationHistory, null);
            log.info("子问题直聊完成 | query='{}' | 耗时={}ms", compact(subQueryIntent.subQuery()), elapsedMs(start));
            return new SubQueryRetrievalResult(
                subQueryIntent.subQuery(), primary, List.of(),
                false, null, chatResponse, 0, 0
            );
        }

        SubQueryRetrievalResult result = subQueryRetrievalService.retrieveForSubQuery(userId, subQueryIntent);
        log.info("子问题RAG执行完成 | query='{}' | results={} | topK={} | 耗时={}ms",
            compact(subQueryIntent.subQuery()), result.retrievedCount(), result.retrievalTopK(), elapsedMs(start));
        return result;
    }

    private void recordHighConfidencePattern(IntentDecision intent, String query) {
        if (intent == null || isBlank(query)) {
            return;
        }
        if (intent.getConfidence() != null
            && intent.getConfidence() >= 0.9
            && (intent.getAction() == IntentDecision.Action.ROUTE_RAG
            || intent.getAction() == IntentDecision.Action.ROUTE_TOOL)) {
            CompletableFuture.runAsync(
                () -> intentPatternService.recordPattern(
                    intent.getLevel1(), intent.getLevel2(), query, intent.getConfidence()),
                chatStreamExecutor
            ).exceptionally(ex -> {
                log.debug("异步写回意图样本失败: {}", ex.getMessage());
                return null;
            });
        }
    }

    private String generateChatDirectResponse(String query,
                                              IntentDecision intent,
                                              List<Map<String, String>> conversationHistory,
                                              Consumer<String> chunkHandler) {
        String promptTemplate = buildChatPromptTemplate(intent);
        String response = ragPromptService.generateRagAnswerWithoutReferences(
            query, promptTemplate, conversationHistory, List.of(), true);
        emit(chunkHandler, response);
        return response;
    }

    private String buildChatPromptTemplate(IntentDecision intent) {
        if (intent != null && !isBlank(intent.getPromptTemplate())) {
            return intent.getPromptTemplate();
        }
        if (intent != null && !isBlank(intent.getLevel2())) {
            return intent.getLevel2();
        }
        return CHAT_FALLBACK_PROMPT;
    }

    private FastPathMode resolveFastPathMode(List<SubQueryRetrievalResult> results, boolean allDirect) {
        if (results == null || results.isEmpty()) {
            return FastPathMode.NONE;
        }
        RagProperties.Prompt promptConfig = ragProperties.getPrompt();
        if (promptConfig != null && promptConfig.isMultiIntentAllDirectTemplateEnabled()) {
            boolean allToolOrClarify = true;
            for (SubQueryRetrievalResult result : results) {
                if (result == null || result.intent() == null || isBlank(result.directResponse())) {
                    allToolOrClarify = false;
                    break;
                }
                IntentDecision.Action action = result.intent().getAction();
                if (action != IntentDecision.Action.ROUTE_TOOL && action != IntentDecision.Action.CLARIFY) {
                    allToolOrClarify = false;
                    break;
                }
            }
            if (allToolOrClarify) {
                return FastPathMode.ALL_DIRECT_TEMPLATE;
            }
        }
        if (allDirect) {
            return FastPathMode.DIRECT;
        }
        if (promptConfig == null || !promptConfig.isMultiIntentStructuredFastPathEnabled()) {
            return FastPathMode.NONE;
        }
        int ragCount = 0;
        for (SubQueryRetrievalResult result : results) {
            if (result == null) {
                continue;
            }
            boolean hasDirect = !isBlank(result.directResponse());
            boolean hasClarify = result.clarifyTriggered() && !isBlank(result.clarifyMessage());
            boolean hasRag = result.sources() != null && !result.sources().isEmpty();
            if (hasRag && !hasDirect && !hasClarify) {
                ragCount++;
            }
        }
        return ragCount <= Math.max(0, promptConfig.getMultiIntentStructuredFastPathMaxRagCount())
            ? FastPathMode.STRUCTURED
            : FastPathMode.NONE;
    }

    private String stitchFastPathResponse(List<SubQueryRetrievalResult> results, FastPathMode mode) {
        StringBuilder builder = new StringBuilder();
        builder.append(mode == FastPathMode.ALL_DIRECT_TEMPLATE
            ? "结合你的问题，当前可直接确认的信息如下：\n"
            : "根据你的问题，整理如下：\n");
        for (int i = 0; i < results.size(); i++) {
            SubQueryRetrievalResult result = results.get(i);
            builder.append(i + 1).append(". ").append(result.query()).append("\n");

            if (!isBlank(result.directResponse())) {
                if (mode == FastPathMode.ALL_DIRECT_TEMPLATE) {
                    builder.append(result.intent() != null && result.intent().getAction() == IntentDecision.Action.CLARIFY
                        ? "需要补充："
                        : "处理结果：");
                }
                builder.append(result.directResponse()).append("\n");
                continue;
            }

            if (result.clarifyTriggered() && !isBlank(result.clarifyMessage())) {
                builder.append("目前还缺少必要信息：").append(result.clarifyMessage()).append("\n");
                continue;
            }

            List<RetrievalMatch> sources = result.sources();
            if (sources == null || sources.isEmpty()) {
                builder.append("暂无可直接引用的资料，请补充更具体场景。\n");
                continue;
            }

            RetrievalMatch first = sources.get(0);
            builder.append("根据资料，");
            String summary = summarizeRetrievedSnippet(first.getTextContent());
            builder.append(summary).append("\n");
        }
        return builder.toString().trim();
    }

    private String summarizeRetrievedSnippet(String text) {
        if (isBlank(text)) {
            return "可参考相关制度或办事说明。";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 90) {
            return normalized;
        }
        int cut = Math.max(
            normalized.lastIndexOf('。', 90),
            Math.max(normalized.lastIndexOf('；', 90), normalized.lastIndexOf('，', 90))
        );
        if (cut < 20) {
            cut = 90;
        }
        return normalized.substring(0, Math.min(cut + 1, normalized.length()));
    }

    private void emit(Consumer<String> chunkHandler, String text) {
        if (chunkHandler != null && text != null) {
            chunkHandler.accept(text);
        }
    }

    private String compact(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }

    private String describeIntent(IntentDecision intent) {
        if (intent == null) {
            return "null";
        }
        return "{action=" + intent.getAction()
            + ",level1=" + intent.getLevel1()
            + ",level2=" + intent.getLevel2()
            + ",tool=" + intent.getToolName()
            + ",score=" + intent.getConfidence()
            + "}";
    }

    private boolean isBlank(String str) {
        return str == null || str.isBlank();
    }

    private boolean hasUsableEvidence(SubQueryRetrievalResult result) {
        if (result == null) {
            return false;
        }
        if (!isBlank(result.directResponse())) {
            return true;
        }
        return result.sources() != null && !result.sources().isEmpty();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private record StreamResult(String response,
                                List<RetrievalMatch> sources,
                                boolean clarifyTriggered,
                                int retrievedCount,
                                int retrievalTopK) {
    }

    private enum FastPathMode {
        NONE,
        DIRECT,
        STRUCTURED,
        ALL_DIRECT_TEMPLATE
    }

    public record ExecutionResult(String response,
                                  List<RetrievalMatch> sources,
                                  boolean clarifyTriggered,
                                  int retrievedCount,
                                  int retrievalTopK,
                                  long rewriteLatencyMs) {
    }
}
