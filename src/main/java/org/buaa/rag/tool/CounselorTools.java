package org.buaa.rag.tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * AI 辅导员工具集合（Function Calling）
 */
@Slf4j
@Component
public class CounselorTools {

    @Tool(
        name = "queryGrade",
        description = "查询学生成绩与绩点信息"
    )
    public GradeToolResult queryGrade(
        @ToolParam(description = "学生ID或学号", required = true) String studentId
    ) {
        log.info("Tool queryGrade called, studentId={}", studentId);
        // TODO: 对接真实教务系统
        return new GradeToolResult(
            safeValue(studentId, "anonymous"),
            3.42,
            96,
            1,
            nowTs()
        );
    }

    @Tool(
        name = "createLeaveDraft",
        description = "创建请假申请草稿"
    )
    public LeaveDraftToolResult createLeaveDraft(
        @ToolParam(description = "用户ID", required = true) String userId,
        @ToolParam(description = "开始时间，例如 2026-03-01", required = false) String startTime,
        @ToolParam(description = "结束时间，例如 2026-03-02", required = false) String endTime,
        @ToolParam(description = "请假事由", required = false) String reason
    ) {
        log.info("Tool createLeaveDraft called, userId={}", userId);
        // TODO: 对接真实请假审批系统
        return new LeaveDraftToolResult(
            safeValue(userId, "anonymous"),
            safeValue(startTime, "待补充"),
            safeValue(endTime, "待补充"),
            safeValue(reason, "待补充"),
            "DRAFT_CREATED",
            "请补充时间并提交学院审批",
            nowTs()
        );
    }

    @Tool(
        name = "createRepairTicket",
        description = "创建宿舍或后勤报修工单草稿"
    )
    public RepairDraftToolResult createRepairTicket(
        @ToolParam(description = "用户ID", required = true) String userId,
        @ToolParam(description = "宿舍号，例如 2号楼305", required = false) String dormitory,
        @ToolParam(description = "故障描述", required = false) String issue
    ) {
        log.info("Tool createRepairTicket called, userId={}", userId);
        // TODO: 对接真实后勤工单系统
        return new RepairDraftToolResult(
            safeValue(userId, "anonymous"),
            safeValue(dormitory, "待补充"),
            safeValue(issue, "待补充"),
            "DRAFT_CREATED",
            "请补充宿舍号和故障详情后提交",
            nowTs()
        );
    }

    private String nowTs() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());
    }

    private String safeValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    public record GradeToolResult(
        String studentId,
        double gpa,
        int creditCompleted,
        int riskCourses,
        String generatedAt
    ) {
    }

    public record LeaveDraftToolResult(
        String userId,
        String startTime,
        String endTime,
        String reason,
        String status,
        String nextAction,
        String generatedAt
    ) {
    }

    public record RepairDraftToolResult(
        String userId,
        String dormitory,
        String issue,
        String status,
        String nextAction,
        String generatedAt
    ) {
    }
}
