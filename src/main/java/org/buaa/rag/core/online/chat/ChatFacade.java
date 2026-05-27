package org.buaa.rag.core.online.chat;

import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.MESSAGE_EMPTY;
import static org.buaa.rag.tool.TextUtils.isBlank;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.intent.IntentRouterService;
import org.buaa.rag.core.online.retrieval.MultiChannelRetrievalEngine;
import org.buaa.rag.core.online.retrieval.SmartRetrieverService;
import org.buaa.rag.core.online.retrieval.postprocessor.RetrievalPostProcessorService;
import org.buaa.rag.core.online.trace.RagTraceRoot;
import org.buaa.rag.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Chat 应用服务实现 —— 协调 SSE 流式对话和独立搜索请求。
 * <p>
 * 实现 {@link ChatService} 接口，使 Controller 层通过标准 Service 接口访问，
 * 避免越级依赖 core 层。
 * <p>
 * 单向依赖链：Controller → ChatService → ChatFacade → StreamChatPipeline → ConversationService。
 */
@Service
@RequiredArgsConstructor
public class ChatFacade implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatFacade.class);

    private final StreamChatPipeline streamChatPipeline;
    private final StreamTaskManager streamTaskManager;
    private final ObjectMapper objectMapper;

    // 检索相关
    private final SmartRetrieverService retrieverService;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final RetrievalPostProcessorService postProcessorService;
    private final IntentRouterService intentRouterService;

    @Qualifier("chatStreamExecutor")
    private final Executor chatStreamExecutor;

    /**
     * 注入自身代理：解决 Spring AOP 自调用（self-invocation）导致切面失效的问题。
     * 使用 @Lazy + setter 注入，因为 Lombok @RequiredArgsConstructor 不传播 @Lazy。
     */
    private ChatFacade self;

    @Autowired
    @Lazy
    public void setSelf(ChatFacade self) {
        this.self = self;
    }

    // ──────────────────────── 流式对话 ────────────────────────

    /**
     * 创建 SSE 连接并异步启动流式对话。
     */
    @Override
    public SseEmitter handleChatStream(String message, Long userId) {
        SseEmitter emitter = new SseEmitter(0L);

        if (isBlank(message)) {
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

        SseStreamChatEventHandler eventHandler = new SseStreamChatEventHandler(emitter, objectMapper);

        emitter.onCompletion(() -> {
            eventHandler.markCancelled();
            streamTaskManager.cancel(taskId);
        });
        emitter.onTimeout(() -> {
            eventHandler.markCancelled();
            streamTaskManager.cancel(taskId);
        });

        CompletableFuture.runAsync(
            () -> self.streamChat(message, resolvedUserId, taskId, eventHandler),
            chatStreamExecutor
        );

        return emitter;
    }

    /**
     * 核心流式对话流程 —— 委托给 {@link StreamChatPipeline}。
     */
    @RagTraceRoot(name = "stream-chat", taskIdArg = "taskId")
    public void streamChat(String message, Long userId, String taskId, StreamChatCallback callback) {
        try {
            StreamChatPipeline.Context ctx = StreamChatPipeline.Context.builder()
                .message(message)
                .userId(resolveUserId(userId))
                .taskId(taskId)
                .callback(callback)
                .build();
            streamChatPipeline.execute(ctx);
        } catch (Exception e) {
            log.error("流式对话处理异常, userId={}, taskId={}", userId, taskId, e);
            callback.onError(e);
        } finally {
            streamTaskManager.unbind(taskId);
        }
    }

    // ──────────────────────── 独立搜索 ────────────────────────

    /**
     * 处理搜索请求，返回检索结果列表。
     */
    @Override
    public List<RetrievalMatch> handleSearchRequest(String query, int topK, Long userId) {
        return handleSearchRequest(query, topK, userId, "full");
    }

    /**
     * 带 mode 参数的检索接口，用于对照实验。
     * mode: es(纯BM25) / vector(纯向量) / hybrid(混合无重排) / full(完整流程含意图路由+重排)
     */
    @Override
    public List<RetrievalMatch> handleSearchRequest(String query, int topK, Long userId, String mode) {
        String userIdStr = String.valueOf(resolveUserId(userId));
        if (mode == null) mode = "full";
        switch (mode) {
            case "es":
                return retrieverService.retrieveTextOnly(query, topK, userIdStr);
            case "vector":
                return retrieverService.retrieveVectorOnly(query, topK, userIdStr);
            case "hybrid": {
                // BM25+向量 RRF融合，不做重排
                List<RetrievalMatch> raw = retrieverService.retrieve(query, topK, userIdStr);
                return raw; // retrieve() 内部已做平袍融合
            }
            default: {
                // full: 意图路由 + 多通道并发检索 + RRF + 重排
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
                return results;
            }
        }
    }

    // ──────────────────────── helpers ────────────────────────

    private Long resolveUserId(Long userId) {
        return userId != null ? userId : UserContext.resolvedUserId();
    }

}
