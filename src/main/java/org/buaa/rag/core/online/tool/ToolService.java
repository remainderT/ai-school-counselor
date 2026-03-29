package org.buaa.rag.core.online.tool;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.tool.CounselorTools;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 工具路由执行器
 */
@Slf4j
@Service
public class ToolService {

    private final CounselorTools counselorTools;

    public ToolService(CounselorTools counselorTools) {
        this.counselorTools = counselorTools;
    }

    public String execute(String userId, String userQuery, IntentDecision decision) {
        if (decision == null || decision.getToolName() == null) {
            return "未找到可用的工具处理该请求。";
        }

        String tool = decision.getToolName();
        return switch (tool) {
            case "leave" -> handleLeave(userId, userQuery);
            case "repair" -> handleRepair(userId, userQuery);
            case "score" -> handleScore(userId);
            case "weather" -> handleWeather(userId, userQuery);
            default -> "该需求暂未接入自动处理，请稍后再试。";
        };
    }

    private String handleLeave(String userId, String userQuery) {
        log.info("触发请假工具, userId={}, query={}", userId, userQuery);
        CounselorTools.LeaveDraftToolResult result = counselorTools.createLeaveDraft(
            safeValue(userId, "anonymous"),
            null,
            null,
            userQuery
        );
        return "已创建请假草稿，状态：" + result.status()
            + "；后续操作：" + result.nextAction();
    }

    private String handleRepair(String userId, String userQuery) {
        log.info("触发报修工具, userId={}, query={}", userId, userQuery);
        CounselorTools.RepairDraftToolResult result = counselorTools.createRepairTicket(
            safeValue(userId, "anonymous"),
            null,
            userQuery
        );
        return "已创建报修草稿，状态：" + result.status()
            + "；后续操作：" + result.nextAction();
    }

    private String handleScore(String userId) {
        log.info("触发成绩查询工具, userId={}", userId);
        CounselorTools.GradeToolResult result = counselorTools.queryGrade(
            safeValue(userId, "anonymous")
        );
        return "成绩查询结果：当前绩点 " + result.gpa()
            + "，已修学分 " + result.creditCompleted()
            + "，风险课程 " + result.riskCourses() + " 门。";
    }

    private String handleWeather(String userId, String userQuery) {
        log.info("触发天气查询工具, userId={}, query={}", userId, userQuery);
        CounselorTools.WeatherToolResult result = counselorTools.queryWeatherByMcp(
            null,
            "current",
            3,
            safeValue(userId, "anonymous"),
            userQuery
        );
        if ("NEED_CITY".equals(result.status())) {
            return result.summary();
        }
        if ("UNAVAILABLE".equals(result.status())) {
            return result.summary();
        }
        String source = result.source() == null || result.source().isBlank() ? "unknown" : result.source();
        return result.summary() + "\n（天气数据来源: " + source + "）";
    }

    private String safeValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
