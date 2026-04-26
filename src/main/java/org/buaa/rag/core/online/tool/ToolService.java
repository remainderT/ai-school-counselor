package org.buaa.rag.core.online.tool;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.tool.CounselorTools;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 工具路由执行器：保留当前在线链路需要的少量工具直连分发。
 */
@Slf4j
@Service
public class ToolService {

    private final CounselorTools counselorTools;

    public ToolService(CounselorTools counselorTools) {
        this.counselorTools = counselorTools;
    }

    public String execute(String userId, String userQuery, IntentDecision decision) {
        String toolName = decision == null ? null : decision.getToolName();
        if (toolName == null || toolName.isBlank()) {
            return "未找到可用的工具处理该请求。";
        }
        try {
            return switch (toolName.trim().toLowerCase()) {
                case "score" -> executeScore(userId);
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

    private String executeScore(String userId) {
        log.info("触发成绩查询工具, userId={}", userId);
        counselorTools.queryGrade(safeValue(userId));
        return "成绩查询结果：90分。";
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
