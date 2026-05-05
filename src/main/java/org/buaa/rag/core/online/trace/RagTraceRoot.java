package org.buaa.rag.core.online.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在一次完整 RAG 请求的入口方法上，触发链路 Trace 的根记录创建。
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
