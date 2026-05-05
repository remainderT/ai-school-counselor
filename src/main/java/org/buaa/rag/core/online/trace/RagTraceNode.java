package org.buaa.rag.core.online.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在 RAG 链路中的关键处理阶段方法上，记录该节点的耗时和状态。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RagTraceNode {

    /** 节点名称（用于前端瀑布图展示） */
    String name() default "";

    /** 节点类型（用于分组统计和颜色区分） */
    String type() default "METHOD";
}
