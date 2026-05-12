package org.buaa.rag.core.online.tool;

import java.util.Map;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.online.tool.mcp.AcademicMcpParameterExtractor;
import org.buaa.rag.core.online.tool.mcp.ExamQueryMcpExecutor;
import org.buaa.rag.core.online.tool.mcp.LocalMcpToolExecutor;
import org.buaa.rag.core.online.tool.mcp.LocalMcpToolRegistry;
import org.buaa.rag.core.online.tool.mcp.ScheduleQueryMcpExecutor;
import org.buaa.rag.core.online.tool.mcp.ScoreQueryMcpExecutor;
import org.buaa.rag.tool.CounselorTools;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 工具路由执行器：保留当前在线链路需要的少量工具直连分发。
 */
@Slf4j
@Service
public class ToolService {

    private final CounselorTools counselorTools;
    private final LocalMcpToolRegistry localMcpToolRegistry;
    private final AcademicMcpParameterExtractor academicMcpParameterExtractor;

    public ToolService(CounselorTools counselorTools,
                       LocalMcpToolRegistry localMcpToolRegistry,
                       AcademicMcpParameterExtractor academicMcpParameterExtractor) {
        this.counselorTools = counselorTools;
        this.localMcpToolRegistry = localMcpToolRegistry;
        this.academicMcpParameterExtractor = academicMcpParameterExtractor;
    }

    public String execute(String userId, String userQuery, IntentDecision decision) {
        String toolName = decision == null ? null : decision.getToolName();
        try {
            String mcpToolId = decision == null ? null : decision.getMcpToolId();
            if (StringUtils.hasText(mcpToolId)) {
                return executeMcpTool(userQuery, mcpToolId, decision.getParamPromptTemplate());
            }
            if (toolName == null || toolName.isBlank()) {
                return "未找到可用的工具处理该请求。";
            }
            return switch (toolName.trim().toLowerCase()) {
                case "score", ScoreQueryMcpExecutor.TOOL_ID -> executeMcpTool(
                    userQuery, ScoreQueryMcpExecutor.TOOL_ID, decision == null ? null : decision.getParamPromptTemplate());
                case "schedule", ScheduleQueryMcpExecutor.TOOL_ID -> executeMcpTool(
                    userQuery, ScheduleQueryMcpExecutor.TOOL_ID, decision == null ? null : decision.getParamPromptTemplate());
                case "exam", ExamQueryMcpExecutor.TOOL_ID -> executeMcpTool(
                    userQuery, ExamQueryMcpExecutor.TOOL_ID, decision == null ? null : decision.getParamPromptTemplate());
                case "leave" -> executeLeave(userId, userQuery);
                case "repair" -> executeRepair(userId, userQuery);
                default -> {
                    log.warn("未找到工具执行器: toolName={}", toolName);
                    yield "该需求暂未接入自动处理，请稍后再试。";
                }
            };
        } catch (Exception e) {
            log.error("工具执行异常: toolName={}, userId={}", toolName, userId, e);
            return "工具执行失败，请稍后重试。";
        }
    }

    private String executeMcpTool(String userQuery, String toolId, String paramPromptTemplate) {
        LocalMcpToolExecutor executor = localMcpToolRegistry.getExecutor(toolId).orElse(null);
        if (executor == null) {
            log.warn("未找到 MCP 工具执行器: toolId={}", toolId);
            return "该教务工具暂未接入，请稍后再试。";
        }
        Map<String, Object> parameters = academicMcpParameterExtractor.extractParameters(
            userQuery, executor.getToolDefinition(), paramPromptTemplate);
        return executor.execute(parameters);
    }

    private String executeLeave(String userId, String userQuery) {
        log.info("触发请假工具, userId={}, query={}", userId, userQuery);
        CounselorTools.LeaveDraftToolResult result = counselorTools.createLeaveDraft(
            safeValue(userId), null, null, userQuery);
        return "已创建请假草稿，状态：" + result.status()
            + "；后续操作：" + result.nextAction();
    }

    private String executeRepair(String userId, String userQuery) {
        log.info("触发报修工具, userId={}, query={}", userId, userQuery);
        CounselorTools.RepairDraftToolResult result = counselorTools.createRepairTicket(
            safeValue(userId), null, userQuery);
        return "已创建报修草稿，状态：" + result.status()
            + "；后续操作：" + result.nextAction();
    }

    private String safeValue(String value) {
        return value == null ? "" : value.trim();
    }
}
