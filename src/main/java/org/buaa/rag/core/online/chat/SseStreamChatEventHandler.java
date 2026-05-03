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
 * <h3>事件类型</h3>
 * <ul>
 *   <li>{@code meta}    - 首帧，携带 messageId + taskId</li>
 *   <li>{@code message} - LLM 内容分块（JSON: {"type":"response","delta":"..."}）</li>
 *   <li>{@code sources} - 检索来源列表（JSON）</li>
 *   <li>{@code finish}  - 完成通知（JSON: {"title":"...","messageId":123}）</li>
 *   <li>{@code done}    - 正常结束标志</li>
 *   <li>{@code error}   - 错误描述</li>
 * </ul>
 *
 * <h3>分块发送</h3>
 * <p>LLM 返回的 token 粒度可能过细（单字符），导致 SSE 帧过于碎片化、
 * 网络开销大。{@code chunkSize} 参数控制内容合并粒度——当累积字符数
 * 达到阈值后才实际发送一帧，降低网络传输次数。设为 1 表示逐字符发送。
 *
 * <p>线程安全：由 {@link #cancelled} 原子标志保护，支持多线程并发写入。
 */
public class SseStreamChatEventHandler implements StreamChatCallback {

    private static final Logger log = LoggerFactory.getLogger(SseStreamChatEventHandler.class);

    /** 默认分块大小（字符数），1 表示不合并 */
    private static final int DEFAULT_CHUNK_SIZE = 1;

    private final ObjectMapper objectMapper;
    private final SseEmitter emitter;
    private final int chunkSize;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    /** 累积缓冲区，用于合并碎片 token */
    private final StringBuilder chunkBuffer = new StringBuilder();

    public SseStreamChatEventHandler(SseEmitter emitter, ObjectMapper objectMapper) {
        this(emitter, objectMapper, DEFAULT_CHUNK_SIZE);
    }

    public SseStreamChatEventHandler(SseEmitter emitter, ObjectMapper objectMapper, int chunkSize) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
        this.chunkSize = Math.max(1, chunkSize);
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
        synchronized (chunkBuffer) {
            chunkBuffer.append(chunk);
            if (chunkBuffer.length() >= chunkSize) {
                flushBuffer();
            }
        }
    }

    /**
     * 强制刷出缓冲区中的剩余内容（在 finish/complete 前调用）。
     */
    private void flushBuffer() {
        String pending;
        synchronized (chunkBuffer) {
            if (chunkBuffer.isEmpty()) {
                return;
            }
            pending = chunkBuffer.toString();
            chunkBuffer.setLength(0);
        }
        sendMessageEvent(pending);
    }

    private void sendMessageEvent(String delta) {
        if (cancelled.get() || delta == null || delta.isEmpty()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(new MessageDelta("response", delta));
            emitter.send(SseEmitter.event().name("message").data(payload));
        } catch (Exception e) {
            log.debug("发送 message 事件失败: {}", e.getMessage());
            markCancelled();
        }
    }

    @Override
    public void onSources(List<RetrievalMatch> sources) {
        flushBuffer(); // 确保所有 LLM 内容已发送
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
    public void onFinish(String title, Long messageId) {
        flushBuffer(); // 确保所有 LLM 内容已发送
        if (cancelled.get()) {
            return;
        }
        try {
            // finish 事件携带对话标题和消息 ID
            String payload = objectMapper.writeValueAsString(
                new FinishPayload(title, messageId)
            );
            emitter.send(SseEmitter.event().name("finish").data(payload));
        } catch (Exception e) {
            log.debug("发送 finish 事件失败: {}", e.getMessage());
        }
    }

    @Override
    public void onComplete() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
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

    public record MessageDelta(String type, String delta) {}

    public record FinishPayload(String title, Long messageId) {}
}
