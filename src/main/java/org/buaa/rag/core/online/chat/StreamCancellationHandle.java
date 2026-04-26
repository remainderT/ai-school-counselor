package org.buaa.rag.core.online.chat;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LLM 流式调用取消句柄（对齐 ragent StreamCancellationHandle）。
 *
 * <p>当 SSE 连接断开或用户主动停止时，调用 {@link #cancel()} 通知 LLM 层
 * 停止消费流，避免在 Pipeline 层只做软判断而 LLM 线程仍在持续读取的问题。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * StreamCancellationHandle handle = new StreamCancellationHandle();
 * llmChat.streamResponseWithHandle(messages, ..., chunkHandler, handle);
 * // 需要取消时：
 * handle.cancel();
 * }</pre>
 */
public class StreamCancellationHandle {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 触发取消，幂等操作。 */
    public void cancel() {
        cancelled.set(true);
    }

    /** 是否已取消。 */
    public boolean isCancelled() {
        return cancelled.get();
    }
}
