package org.buaa.rag.core.online.tool;

import org.buaa.rag.core.model.IntentDecision;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具路由执行器（门面）：委托 {@link ToolExecutorRegistry} 完成实际路由和执行。
 *
 * <p>保留此类是为了对调用方（{@code OnlineChatOrchestrator}）保持原有接口不变。
 * 新增工具时，只需在 {@code impl/} 包下实现 {@link ToolExecutor} 并标注 {@code @Component}，
 * 无需修改此类或 {@link ToolExecutorRegistry}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolService {

    private final ToolExecutorRegistry registry;

    /**
     * 根据意图决策路由到对应工具执行器并执行。
     */
    public String execute(String userId, String userQuery, IntentDecision decision) {
        return registry.execute(userId, userQuery, decision);
    }
}
