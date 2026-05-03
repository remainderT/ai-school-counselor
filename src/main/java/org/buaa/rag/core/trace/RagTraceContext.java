package org.buaa.rag.core.trace;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * RAG 链路 Trace 上下文。
 */
public final class RagTraceContext {

    private static final ThreadLocal<String>        TRACE_ID   = new ThreadLocal<>();
    private static final ThreadLocal<String>        TASK_ID    = new ThreadLocal<>();
    private static final ThreadLocal<Deque<String>> NODE_STACK = new ThreadLocal<>();

    private RagTraceContext() {}

    // ── traceId ──────────────────────────────────────────────────────────────

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static boolean hasActiveTrace() {
        String traceId = TRACE_ID.get();
        return traceId != null && !traceId.isBlank();
    }

    // ── taskId ───────────────────────────────────────────────────────────────

    public static String getTaskId() {
        return TASK_ID.get();
    }

    public static void setTaskId(String taskId) {
        TASK_ID.set(taskId);
    }

    // ── nodeStack ─────────────────────────────────────────────────────────────

    /** 当前节点的父节点 ID（栈顶），null 表示当前节点是根的直接子节点 */
    public static String currentNodeId() {
        Deque<String> stack = NODE_STACK.get();
        return (stack == null || stack.isEmpty()) ? null : stack.peek();
    }

    /** 当前节点深度（栈深度） */
    public static int depth() {
        Deque<String> stack = NODE_STACK.get();
        return stack == null ? 0 : stack.size();
    }

    /** 节点开始时入栈 */
    public static void pushNode(String nodeId) {
        Deque<String> stack = NODE_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            NODE_STACK.set(stack);
        }
        stack.push(nodeId);
    }

    /** 节点结束时出栈（无论成功还是失败，在 finally 中调用） */
    public static void popNode() {
        Deque<String> stack = NODE_STACK.get();
        if (stack != null && !stack.isEmpty()) {
            stack.pop();
        }
    }

    // ── 跨线程传播 ─────────────────────────────────────────────────────────────

    /**
     * 在父线程中调用，捕获当前 Trace 上下文的快照。
     * 快照只复制 traceId 和 taskId（nodeStack 不跨线程共享，子线程从 depth=0 开始）。
     */
    public static Snapshot capture() {
        return new Snapshot(TRACE_ID.get(), TASK_ID.get());
    }

    /**
     * 在子线程执行开始时调用，将父线程的 traceId / taskId 恢复到当前线程。
     */
    public static void restore(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        if (snapshot.traceId != null) {
            TRACE_ID.set(snapshot.traceId);
        }
        if (snapshot.taskId != null) {
            TASK_ID.set(snapshot.taskId);
        }
    }

    /**
     * Trace 上下文快照，用于跨线程传播。
     */
    public record Snapshot(String traceId, String taskId) {}

    // ── 清理 ──────────────────────────────────────────────────────────────────

    /**
     * 链路结束后必须调用，清除三个 ThreadLocal 变量，防止内存泄漏。
     * 在 {@code @RagTraceRoot} 切面的 {@code finally} 块中调用。
     */
    public static void clear() {
        TRACE_ID.remove();
        TASK_ID.remove();
        NODE_STACK.remove();
    }
}
