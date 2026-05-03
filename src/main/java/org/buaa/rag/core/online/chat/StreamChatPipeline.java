package org.buaa.rag.core.online.chat;

import java.util.List;
import java.util.Map;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.online.intent.IntentResolutionService;
import org.buaa.rag.core.online.intent.SubQueryIntent;
import org.buaa.rag.core.online.retrieval.SubQueryRetrievalService;
import org.buaa.rag.core.online.rewrite.QueryRewriteAndSplitService;
import org.buaa.rag.core.online.rewrite.QueryRewriteResult;
import org.buaa.rag.service.ConversationService;
import org.buaa.rag.tool.LlmChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Service
public class StreamChatPipeline {

    private static final Logger log = LoggerFactory.getLogger(StreamChatPipeline.class);

    /** 纯系统意图（闲聊/问候）默认系统提示 */
    private static final String SYSTEM_CHAT_PROMPT =
            "你是北航智能辅导员。对于问候或闲聊请简短友好回应；若用户尚未给出具体诉求，礼貌引导其说明想咨询的事项。";

    private final ConversationService conversationService;
    private final QueryRewriteAndSplitService queryRewriteAndSplitService;
    private final IntentResolutionService intentResolutionService;
    private final RouteExecutionCoordinator routeExecutionCoordinator;
    private final StreamTaskManager streamTaskManager;
    private final SubQueryRetrievalService subQueryRetrievalService;
    private final LlmChat llmChat;

    public StreamChatPipeline(ConversationService conversationService,
                              QueryRewriteAndSplitService queryRewriteAndSplitService,
                              IntentResolutionService intentResolutionService,
                              RouteExecutionCoordinator routeExecutionCoordinator,
                              StreamTaskManager streamTaskManager,
                              SubQueryRetrievalService subQueryRetrievalService,
                              LlmChat llmChat) {
        this.conversationService = conversationService;
        this.queryRewriteAndSplitService = queryRewriteAndSplitService;
        this.intentResolutionService = intentResolutionService;
        this.routeExecutionCoordinator = routeExecutionCoordinator;
        this.streamTaskManager = streamTaskManager;
        this.subQueryRetrievalService = subQueryRetrievalService;
        this.llmChat = llmChat;
    }

    public ExecutionResult execute(Context ctx) {
        prepareSession(ctx);
        loadConversationHistory(ctx);
        rewriteQuery(ctx);
        resolveIntents(ctx);
        buildRoutingPlan(ctx);

        // ── 短路1: 歧义引导 ──
        String guidanceQuestion = detectGuidanceQuestion(ctx.getSubQueryIntents());
        ExecutionResult result;
        if (guidanceQuestion != null && !guidanceQuestion.isBlank()) {
            ctx.getCallback().onContent(guidanceQuestion);
            result = new ExecutionResult(
                guidanceQuestion,
                List.of(),
                true,
                0,
                0,
                ctx.getRewriteResult().latencyMs()
            );
            completeSession(ctx, result);
            return result;
        }

        // ── 短路2: 纯系统意图（闲聊/问候），跳过检索直接生成 ──
        if (isAllSystemChat(ctx.getSubQueryIntents())) {
            log.info("纯系统意图短路 | query='{}'", compact(ctx.getMessage()));
            StreamCancellationHandle cancelHandle = new StreamCancellationHandle();
            ctx.setCancelHandle(cancelHandle);
            String systemResponse = streamSystemDirectResponse(
                    ctx.getRewrittenQuery(), ctx.getConversationHistory(), ctx.getSubQueryIntents(),
                    chunk -> {
                        if (ctx.getCallback().isCancelled()) {
                            cancelHandle.cancel();
                            return;
                        }
                        ctx.getCallback().onContent(chunk);
                    },
                    cancelHandle);
            result = new ExecutionResult(
                    systemResponse, List.of(), false, 0, 0,
                    ctx.getRewriteResult().latencyMs());
            completeSession(ctx, result);
            return result;
        }

        // ── 正常路由执行 ──
        StreamCancellationHandle cancelHandle = new StreamCancellationHandle();
        ctx.setCancelHandle(cancelHandle);

        result = ExecutionResult.from(routeExecutionCoordinator.execute(
            String.valueOf(ctx.getUserId()),
            ctx.getMessage(),
            ctx.getConversationHistory(),
            ctx.getRewrittenQuery(),
            ctx.getSubQueryIntents(),
            ctx.getRewriteResult().latencyMs(),
            chunk -> {
                if (ctx.getCallback().isCancelled()) {
                    cancelHandle.cancel();
                    return;
                }
                ctx.getCallback().onContent(chunk);
            },
            cancelHandle
        ));

        completeSession(ctx, result);
        return result;
    }

    private void prepareSession(Context ctx) {
        String sessionId = conversationService.obtainOrCreateSession(ctx.getUserId());
        ctx.setSessionId(sessionId);
        conversationService.appendUserMessage(sessionId, ctx.getUserId(), ctx.getMessage());

        // 在流式回答开始前同步生成标题（与 ragent 对齐），
        // 将标题生成的耗时隐藏在回答启动阶段，避免回答结束后用户额外等待。
        String title = conversationService.generateAndPersistTitle(sessionId, ctx.getMessage());
        ctx.setGeneratedTitle(title);

        Long assistantMessageId = conversationService.createAssistantPlaceholder(sessionId, ctx.getUserId());
        ctx.setAssistantMessageId(assistantMessageId);
        ctx.getCallback().onMeta(assistantMessageId, ctx.getTaskId());

        streamTaskManager.bindCancel(ctx.getTaskId(), () -> {
            try {
                conversationService.failAssistantMessage(
                    sessionId,
                    assistantMessageId,
                    ctx.getUserId(),
                    "（用户已取消请求）"
                );
            } catch (Exception ignored) {
            }
        });
    }

    private void loadConversationHistory(Context ctx) {
        List<Map<String, String>> history = conversationService.loadConversationContext(ctx.getSessionId());
        ctx.setConversationHistory(history);
    }

    private void rewriteQuery(Context ctx) {
        QueryRewriteResult rewriteResult = queryRewriteAndSplitService.rewriteWithSplit(
            ctx.getMessage(),
            ctx.getConversationHistory()
        );
        ctx.setRewriteResult(rewriteResult);
    }

    private void resolveIntents(Context ctx) {
        QueryRewriteResult rewriteResult = ctx.getRewriteResult();
        List<SubQueryIntent> subQueryIntents = rewriteResult == null
            ? List.of()
            : intentResolutionService.resolve(
            String.valueOf(ctx.getUserId()),
            rewriteResult.effectiveSubQuestions()
        );
        ctx.setSubQueryIntents(subQueryIntents);
    }

    private void buildRoutingPlan(Context ctx) {
        QueryRewriteResult rewriteResult = ctx.getRewriteResult();
        List<SubQueryIntent> resolvedSubQueries = ctx.getSubQueryIntents();
        if (resolvedSubQueries == null || resolvedSubQueries.isEmpty()) {
            resolvedSubQueries = List.of(new SubQueryIntent(
                rewriteResult.rewrittenQuery(),
                List.of(subQueryRetrievalService.defaultHybridIntent())
            ));
            ctx.setSubQueryIntents(resolvedSubQueries);
        }
        ctx.setRewrittenQuery(rewriteResult.rewrittenQuery());
        log.info("路由计划生成完成 | query='{}' | subQueries={}",
            compact(ctx.getMessage()),
            resolvedSubQueries.size());
    }

    private void completeSession(Context ctx, ExecutionResult result) {
        conversationService.completeAssistantMessage(
            ctx.getSessionId(),
            ctx.getAssistantMessageId(),
            ctx.getUserId(),
            result.response(),
            result.sources()
        );
        ctx.getCallback().onSources(result.sources());
        // 标题已在 prepareSession 阶段通过 LLM 生成并持久化，这里直接使用
        ctx.getCallback().onFinish(ctx.getGeneratedTitle(), ctx.getAssistantMessageId());
        ctx.getCallback().onComplete();
    }

    /**
     * 判断所有子问题是否都是纯系统意图（闲聊/问候），无需走知识库检索。
     * 参考 ragent 的 handleSystemOnly 短路设计。
     */
    private boolean isAllSystemChat(List<SubQueryIntent> subQueryIntents) {
        if (subQueryIntents == null || subQueryIntents.isEmpty()) {
            return false;
        }
        return subQueryIntents.stream().allMatch(sq -> {
            if (sq.candidates() == null || sq.candidates().isEmpty()) {
                return false;
            }
            IntentDecision primary = sq.candidates().get(0);
            return primary != null && primary.getAction() == IntentDecision.Action.ROUTE_CHAT;
        });
    }

    /**
     * 纯系统意图的流式响应：跳过检索，直接用 LLM 生成闲聊/引导回复。
     * 优先使用意图节点上配置的自定义 promptTemplate，否则使用默认系统聊天提示。
     */
    private String streamSystemDirectResponse(String query,
                                               List<Map<String, String>> conversationHistory,
                                               List<SubQueryIntent> subQueryIntents,
                                               java.util.function.Consumer<String> chunkHandler,
                                               StreamCancellationHandle cancelHandle) {
        // 尝试从意图节点获取自定义 prompt
        String customPrompt = subQueryIntents.stream()
                .flatMap(sq -> sq.candidates() == null ? java.util.stream.Stream.empty() : sq.candidates().stream())
                .map(IntentDecision::getPromptTemplate)
                .filter(p -> p != null && !p.isBlank())
                .findFirst()
                .orElse(null);

        String systemPrompt = (customPrompt != null && !customPrompt.isBlank())
                ? customPrompt : SYSTEM_CHAT_PROMPT;

        StringBuilder responseBuilder = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<Throwable> streamError = new java.util.concurrent.atomic.AtomicReference<>();
        llmChat.streamResponseWithHandle(
                buildSystemChatMessages(systemPrompt, conversationHistory, query),
                0.7, 0.9, 800,
                chunk -> {
                    responseBuilder.append(chunk);
                    if (chunkHandler != null) {
                        chunkHandler.accept(chunk);
                    }
                },
                streamError::set,
                () -> {},
                cancelHandle
        );
        Throwable err = streamError.get();
        if (err != null) {
            log.warn("系统意图直聊异常: {}", err.getMessage());
            String fallback = "你好！有什么可以帮你的吗？";
            if (chunkHandler != null && responseBuilder.isEmpty()) {
                chunkHandler.accept(fallback);
            }
            return responseBuilder.isEmpty() ? fallback : responseBuilder.toString();
        }
        return responseBuilder.toString();
    }

    private List<Map<String, String>> buildSystemChatMessages(String systemPrompt,
                                                                List<Map<String, String>> history,
                                                                String question) {
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) {
            messages.addAll(llmChat.toStructuredHistory(history));
        }
        messages.add(Map.of("role", "user", "content", question != null ? question : ""));
        return messages;
    }

    private String detectGuidanceQuestion(List<SubQueryIntent> subQueryIntents) {
        if (subQueryIntents == null || subQueryIntents.isEmpty()) {
            return null;
        }
        for (SubQueryIntent subQueryIntent : subQueryIntents) {
            IntentDecision primary = subQueryRetrievalService.selectPrimaryIntent(subQueryIntent);
            if (primary != null
                && primary.getAction() == IntentDecision.Action.CLARIFY
                && primary.getClarifyQuestion() != null
                && !primary.getClarifyQuestion().isBlank()) {
                return primary.getClarifyQuestion();
            }
        }
        return null;
    }

    private String compact(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }

    @Getter
    @Builder
    public static class Context {

        private final String message;
        private final Long userId;
        private final String taskId;
        private final StreamChatCallback callback;

        @Setter
        private String sessionId;

        @Setter
        private Long assistantMessageId;

        @Setter
        private List<Map<String, String>> conversationHistory;

        @Setter
        private QueryRewriteResult rewriteResult;

        @Setter
        private List<SubQueryIntent> subQueryIntents;

        @Setter
        private String rewrittenQuery;

        @Setter
        private StreamCancellationHandle cancelHandle;

        /** prepareSession 阶段 LLM 生成的会话标题，completeSession 时直接使用 */
        @Setter
        private String generatedTitle;
    }

    public record ExecutionResult(String response,
                                  List<org.buaa.rag.core.model.RetrievalMatch> sources,
                                  boolean clarifyTriggered,
                                  int retrievedCount,
                                  int retrievalTopK,
                                  long rewriteLatencyMs) {

        static ExecutionResult from(RouteExecutionCoordinator.ExecutionResult result) {
            return new ExecutionResult(
                result.response(),
                result.sources(),
                result.clarifyTriggered(),
                result.retrievedCount(),
                result.retrievalTopK(),
                result.rewriteLatencyMs()
            );
        }
    }
}
