package org.buaa.rag.core.online.tool;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.buaa.rag.core.model.IntentDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 工具执行器注册表：在应用启动时自动扫描所有 {@link ToolExecutor} Bean，
 * 按 {@link ToolExecutor#toolName()} 建立路由表。
 *
 * <p>与旧版 {@code ToolService} switch-case 对比：
 * <ul>
 *   <li>新增工具：实现接口 + 标注 {@code @Component}，无需改此类</li>
 *   <li>删除工具：删除对应 Bean 即可，路由表自动更新</li>
 * </ul>
 */
@Component
public class ToolExecutorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutorRegistry.class);

    private final List<ToolExecutor> executors;
    private Map<String, ToolExecutor> registry;

    public ToolExecutorRegistry(List<ToolExecutor> executors) {
        this.executors = executors;
    }

    @PostConstruct
    public void init() {
        this.registry = executors.stream()
            .filter(e -> e.toolName() != null && !e.toolName().isBlank())
            .collect(Collectors.toMap(
                ToolExecutor::toolName,
                Function.identity(),
                (existing, duplicate) -> {
                    log.warn("工具名冲突，保留第一个注册: toolName={}", existing.toolName());
                    return existing;
                }
            ));
        log.info("工具注册表初始化完成，已注册工具: {}", registry.keySet());
    }

    /**
     * 根据工具名查找执行器。
     *
     * @param toolName 工具名
     * @return 对应执行器，不存在时返回 null
     */
    public ToolExecutor find(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        return registry.get(toolName);
    }

    /**
     * 执行工具，找不到时返回默认错误消息。
     */
    public String execute(String userId, String userQuery, IntentDecision decision) {
        if (decision == null || decision.getToolName() == null) {
            return "未找到可用的工具处理该请求。";
        }
        ToolExecutor executor = find(decision.getToolName());
        if (executor == null) {
            log.warn("未找到工具执行器: toolName={}", decision.getToolName());
            return "该需求暂未接入自动处理，请稍后再试。";
        }
        try {
            return executor.execute(userId, userQuery, decision);
        } catch (Exception e) {
            log.error("工具执行异常: toolName={}, userId={}", decision.getToolName(), userId, e);
            return "工具执行失败，请稍后重试。";
        }
    }
}
