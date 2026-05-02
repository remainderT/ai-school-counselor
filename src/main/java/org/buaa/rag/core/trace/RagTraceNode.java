package org.buaa.rag.core.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在 RAG 链路中的关键处理阶段方法上，记录该节点的耗时和状态。
 *
 * <p>切面会：
 * <ol>
 *   <li>从 {@link RagTraceContext} 读取当前 traceId（无 traceId 则跳过）</li>
 *   <li>以栈顶节点为父节点，在 {@code t_rag_trace_node} 插入 RUNNING 记录</li>
 *   <li>将当前节点 ID 入栈（支持任意层级嵌套）</li>
 *   <li>方法结束后出栈并更新 SUCCESS/ERROR 状态和耗时</li>
 * </ol>
 *
 * <p>节点类型建议值（{@code type} 字段）：
 * <ul>
 *   <li>{@code REWRITE} — 查询改写阶段</li>
 *   <li>{@code INTENT} — 意图解析阶段</li>
 *   <li>{@code RETRIEVE} — 检索引擎总入口</li>
 *   <li>{@code RETRIEVE_CHANNEL} — 多通道并行检索</li>
 *   <li>{@code LLM_CHAT} — LLM 生成阶段</li>
 *   <li>{@code METHOD} — 默认值（未分类节点）</li>
 * </ul>
 *
 * <p>示例：
 * <pre>{@code
 * @RagTraceNode(name = "intent-resolve", type = "INTENT")
 * public List<SubQueryIntent> resolve(String userId, List<String> subQueries) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RagTraceNode {

    /** 节点名称（用于前端瀑布图展示） */
    String name() default "";

    /** 节点类型（用于分组统计和颜色区分） */
    String type() default "METHOD";
}
