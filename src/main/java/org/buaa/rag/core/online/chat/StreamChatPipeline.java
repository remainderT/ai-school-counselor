package org.buaa.rag.core.online.chat;

import java.util.List;
import java.util.Map;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.online.intent.IntentResolutionService;
import org.buaa.rag.core.online.intent.SubQueryIntent;
import org.buaa.rag.core.online.retrieval.SubQueryRetrievalService;
import org.buaa.rag.core.online.rewrite.QueryRewriteAndSplitService;
import org.buaa.rag.core.online.rewrite.QueryRewriteResult;
import org.buaa.rag.service.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Service
public class StreamChatPipeline {

    private static final Logger log = LoggerFactory.getLogger(StreamChatPipeline.class);

    private final ConversationService conversationService;
    private final QueryRewriteAndSplitService queryRewriteAndSplitService;
    private final IntentResolutionService intentResolutionService;
    private final RouteExecutionCoordinator routeExecutionCoordinator;
    private final StreamTaskManager streamTaskManager;
    private final SubQueryRetrievalService subQueryRetrievalService;

    public StreamChatPipeline(ConversationService conversationService,
                              QueryRewriteAndSplitService queryRewriteAndSplitService,
                              IntentResolutionService intentResolutionService,
                              RouteExecutionCoordinator routeExecutionCoordinator,
                              StreamTaskManager streamTaskManager,
                              SubQueryRetrievalService subQueryRetrievalService) {
        this.conversationService = conversationService;
        this.queryRewriteAndSplitService = queryRewriteAndSplitService;
        this.intentResolutionService = intentResolutionService;
        this.routeExecutionCoordinator = routeExecutionCoordinator;
        this.streamTaskManager = streamTaskManager;
        this.subQueryRetrievalService = subQueryRetrievalService;
    }

    public ExecutionResult execute(Context ctx) {
        prepareSession(ctx);
        loadConversationHistory(ctx);
        rewriteQuery(ctx);
        resolveIntents(ctx);
        buildRoutingPlan(ctx);

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
        } else {
            // 创建 LLM 层取消句柄并绑定到 SSE 回调（对齐 ragent StreamCancellationHandle）
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
                        // 同步通知 LLM 层停止读取
                        cancelHandle.cancel();
                        return;
                    }
                    ctx.getCallback().onContent(chunk);
                },
                cancelHandle
            ));
        }

        completeSession(ctx, result);
        return result;
    }

    private void prepareSession(Context ctx) {
        String sessionId = conversationService.obtainOrCreateSession(ctx.getUserId());
        ctx.setSessionId(sessionId);
        conversationService.appendUserMessage(sessionId, ctx.getUserId(), ctx.getMessage());
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
        // 生成对话标题：取问题前 20 个字符
        String title = buildConversationTitle(ctx.getMessage());
        ctx.getCallback().onFinish(title, ctx.getAssistantMessageId());
        ctx.getCallback().onComplete();
    }

    private String buildConversationTitle(String message) {
        if (message == null || message.isBlank()) {
            return "新对话";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > 20 ? normalized.substring(0, 20) + "..." : normalized;
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
