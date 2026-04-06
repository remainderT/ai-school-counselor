package org.buaa.rag.core.online.tool;

import org.buaa.rag.core.model.IntentDecision;

/**
 * 工具执行器接口：每个业务工具实现此接口并注册为 Spring Bean，
 * 由 {@link ToolExecutorRegistry} 在启动时自动扫描注册。
 *
 * <p>新增工具只需：
 * <ol>
 *   <li>实现此接口并标注 {@code @Component}</li>
 *   <li>在 {@link #toolName()} 返回唯一工具名</li>
 * </ol>
 * 无需修改任何已有代码（开闭原则）。
 */
public interface ToolExecutor {

    /**
     * 工具唯一名称，与意图决策中的 {@link IntentDecision#getToolName()} 对应。
     */
    String toolName();

    /**
     * 执行工具并返回文本结果。
     *
     * @param userId    当前用户ID
     * @param userQuery 原始用户问题（可用于参数提取）
     * @param decision  意图决策（包含额外的上下文信息）
     * @return 工具执行结果文本
     */
    String execute(String userId, String userQuery, IntentDecision decision);

    /**
     * 安全取值：null 返回空串，否则 trim。
     */
    default String safeValue(String value) {
        return value == null ? "" : value.trim();
    }
}
