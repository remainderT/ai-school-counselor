package org.buaa.rag.core.online.chat;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.buaa.rag.core.model.RetrievalMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 基于 SSE 的流式事件处理器：实现 {@link StreamChatCallback}，
 * 将业务事件翻译为标准 SSE 帧发送给前端。
 *
 * <p>事件类型定义：
 * <ul>
 *   <li>{@code meta}    - 首帧，携带 messageId + taskId</li>
 *   <li>{@code message} - LLM 内容分块</li>
 *   <li>{@code sources} - 检索来源列表（JSON）</li>
 *   <li>{@code done}    - 正常结束标志</li>
 *   <li>{@code error}   - 错误描述</li>
 * </ul>
 *
 * <p>线程安全：由 {@link #cancelled} 原子标志保护，支持多线程并发写入。
 */
public class SseStreamChatEventHandler implements StreamChatCallback {

    private static final Logger log = LoggerFactory.getLogger(SseStreamChatEventHandler.class);
    private final ObjectMapper objectMapper;

    private final SseEmitter emitter;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    public SseStreamChatEventHandler(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMeta(Long messageId, String taskId) {
        if (cancelled.get()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(
                new MetaPayload(messageId, taskId)
            );
            emitter.send(SseEmitter.event().name("meta").data(payload));
        } catch (Exception e) {
            log.debug("发送 meta 事件失败: {}", e.getMessage());
            markCancelled();
        }
    }

    @Override
    public void onContent(String chunk) {
        if (cancelled.get() || chunk == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("message").data(chunk));
        } catch (Exception e) {
            log.debug("发送 message 事件失败: {}", e.getMessage());
            markCancelled();
        }
    }

    @Override
    public void onSources(List<RetrievalMatch> sources) {
        if (cancelled.get()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(sources != null ? sources : List.of());
            emitter.send(SseEmitter.event().name("sources").data(payload));
        } catch (Exception e) {
            log.debug("发送 sources 事件失败: {}", e.getMessage());
        }
    }

    @Override
    public void onComplete() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("done").data(""));
        } catch (Exception e) {
            log.debug("发送 done 事件失败: {}", e.getMessage());
        } finally {
            emitter.complete();
        }
    }

    @Override
    public void onError(Throwable cause) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            String message = cause != null ? cause.getMessage() : "未知错误";
            emitter.send(SseEmitter.event().name("error").data("对话服务异常: " + message));
        } catch (Exception e) {
            log.debug("发送 error 事件失败: {}", e.getMessage());
        } finally {
            emitter.completeWithError(cause != null ? cause : new RuntimeException("对话异常"));
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 标记为已取消（由 SSE onCompletion/onTimeout 回调触发）。
     */
    public void markCancelled() {
        cancelled.set(true);
    }

    // ──────────────────────── payload records ────────────────────────

    public record MetaPayload(Long messageId, String taskId) {}
}
