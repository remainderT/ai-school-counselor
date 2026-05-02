package org.buaa.rag.core.trace;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * RAG 链路 Trace 上下文。
 *
 * <p>使用 {@link ThreadLocal} 存储当前线程的链路状态：
 * <ul>
 *   <li>{@code traceId}：全局链路唯一标识，在 {@code @RagTraceRoot} 入口创建</li>
 *   <li>{@code taskId}：业务任务 ID（与 SSE taskId 绑定）</li>
 *   <li>{@code nodeStack}：节点 ID 栈，维护父子关系和深度</li>
 * </ul>
 *
 * <p><b>注意：</b>RAG 在线链路内部有 CompletableFuture 异步并发，但 AOP 节点记录
 * 均在调用线程内同步完成（入栈/出栈在同一线程），因此标准 ThreadLocal 足够。
 * 若未来需要跨线程透传（如子线程也需要记录节点），可升级为 Alibaba TTL。
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
