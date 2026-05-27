package org.buaa.rag.core.online.chat;

import java.util.Map;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.online.mcp.AcademicMcpParameterExtractor;
import org.buaa.rag.core.online.mcp.LocalMcpToolDefinition;
import org.buaa.rag.core.online.mcp.LocalMcpToolExecutor;
import org.buaa.rag.core.online.mcp.LocalMcpToolRegistry;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolService {

    private final LocalMcpToolRegistry toolRegistry;
    private final AcademicMcpParameterExtractor parameterExtractor;

    public String execute(String userId, String query, IntentDecision intent) {
        String toolId = resolveToolId(intent);
        if (!StringUtils.hasText(toolId)) {
            log.warn("工具路由缺少工具ID, userId={}, query={}", userId, query);
            return "未能确定需要调用的工具，请补充要查询的具体事项。";
        }

        return toolRegistry.getExecutor(toolId)
            .map(executor -> executeTool(userId, query, intent, executor))
            .orElseGet(() -> {
                log.warn("未找到本地 MCP 工具执行器, userId={}, toolId={}, query={}", userId, toolId, query);
                return "当前暂不支持该动态数据查询：" + toolId;
            });
    }

    private String executeTool(String userId,
                               String query,
                               IntentDecision intent,
                               LocalMcpToolExecutor executor) {
        LocalMcpToolDefinition definition = executor.getToolDefinition();
        Map<String, Object> parameters = parameterExtractor.extractParameters(
            query,
            definition,
            intent == null ? null : intent.getParamPromptTemplate()
        );
        try {
            String result = executor.execute(parameters);
            if (StringUtils.hasText(result)) {
                return result;
            }
            log.warn("本地 MCP 工具返回空结果, userId={}, toolId={}, params={}",
                userId, executor.getToolId(), parameters);
            return "工具已执行，但暂未查询到可用结果。";
        } catch (Exception e) {
            log.warn("本地 MCP 工具执行失败, userId={}, toolId={}, params={}",
                userId, executor.getToolId(), parameters, e);
            return "动态数据查询失败：" + e.getMessage();
        }
    }

    private String resolveToolId(IntentDecision intent) {
        if (intent == null) {
            return null;
        }
        if (StringUtils.hasText(intent.getMcpToolId())) {
            return intent.getMcpToolId().trim();
        }
        if (StringUtils.hasText(intent.getToolName())) {
            return intent.getToolName().trim();
        }
        return null;
    }
}
