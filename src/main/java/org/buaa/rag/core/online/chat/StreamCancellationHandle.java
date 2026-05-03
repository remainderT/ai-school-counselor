package org.buaa.rag.core.online.chat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式对话的取消控制句柄。
 *
 * <p>在 LLM 流式输出过程中，SSE 连接断开、用户手动停止或超时等场景
 * 均可通过本句柄发起取消信号。LLM 读取线程在消费每个 token 前轮询
 * {@link #isCancelled()} 以决定是否提前退出。
 *
 * <p>与简单的布尔标志不同，本实现记录了取消原因（{@link CancelReason}）
 * 和取消时间戳，便于日志追踪和诊断统计。
 *
 * <h3>线程安全</h3>
 * <p>使用 {@link AtomicReference} 保证多线程下的可见性和幂等语义——
 * 仅首次取消生效，后续调用静默忽略。
 */
public class StreamCancellationHandle {

    /** 取消原因枚举 */
    public enum CancelReason {
        /** 客户端主动断开 SSE 连接 */
        CLIENT_DISCONNECT,
        /** 用户通过停止接口主动取消 */
        USER_STOP,
        /** 服务端超时 */
        TIMEOUT,
        /** 系统异常导致的强制取消 */
        INTERNAL_ERROR
    }

    /** 取消快照，包含原因和时间戳 */
    public record CancelSnapshot(CancelReason reason, Instant cancelledAt) {}

    private final AtomicReference<CancelSnapshot> snapshot = new AtomicReference<>(null);

    /**
     * 发起取消（幂等操作），仅首次调用生效。
     *
     * @param reason 取消原因
     * @return {@code true} 表示本次调用实际执行了取消；{@code false} 表示已被取消过
     */
    public boolean cancel(CancelReason reason) {
        return snapshot.compareAndSet(null,
                new CancelSnapshot(reason != null ? reason : CancelReason.INTERNAL_ERROR, Instant.now()));
    }

    /** 无原因取消（兼容旧调用点） */
    public boolean cancel() {
        return cancel(CancelReason.CLIENT_DISCONNECT);
    }

    /** 是否已被取消。 */
    public boolean isCancelled() {
        return snapshot.get() != null;
    }

    /** 获取取消快照（未取消时返回 {@code null}）。 */
    public CancelSnapshot snapshot() {
        return snapshot.get();
    }
}
