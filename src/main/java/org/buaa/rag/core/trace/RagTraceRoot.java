package org.buaa.rag.core.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在一次完整 RAG 请求的入口方法上，触发链路 Trace 的根记录创建。
 *
 * <p>切面会：
 * <ol>
 *   <li>生成 traceId 并写入 {@link RagTraceContext}</li>
 *   <li>在 {@code t_rag_trace_run} 表中插入一条 RUNNING 状态的记录</li>
 *   <li>方法返回后更新为 SUCCESS 并记录耗时；异常时更新为 ERROR</li>
 *   <li>在 finally 中清理 {@link RagTraceContext}，防止内存泄漏</li>
 * </ol>
 *
 * <p>示例：
 * <pre>{@code
 * @RagTraceRoot(name = "stream-chat", taskIdArg = "taskId", conversationIdArg = "sessionId")
 * public void streamChat(String sessionId, String taskId, ...) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RagTraceRoot {

    /** 链路名称（用于前端展示） */
    String name() default "";

    /** 会话 ID 的参数名（切面按名称从方法参数中提取） */
    String conversationIdArg() default "sessionId";

    /** 任务 ID 的参数名 */
    String taskIdArg() default "taskId";
}
