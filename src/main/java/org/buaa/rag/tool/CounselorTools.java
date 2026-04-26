package org.buaa.rag.tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 问答助手工具集合
 */
@Slf4j
@Component
public class CounselorTools {

    public GradeToolResult queryGrade(
        String studentId
    ) {
        log.info("Tool queryGrade called, studentId={}", studentId);
        // TODO: 对接真实教务系统
        return new GradeToolResult(
            orDefault(studentId, "anonymous"),
            3.42,
            96,
            1,
            nowTs()
        );
    }

    public LeaveDraftToolResult createLeaveDraft(
        String userId,
        String startTime,
        String endTime,
        String reason
    ) {
        log.info("Tool createLeaveDraft called, userId={}", userId);
        // TODO: 对接真实请假审批系统
        return new LeaveDraftToolResult(
            orDefault(userId, "anonymous"),
            orDefault(startTime, "待补充"),
            orDefault(endTime, "待补充"),
            orDefault(reason, "待补充"),
            "DRAFT_CREATED",
            "请补充时间并提交学院审批",
            nowTs()
        );
    }

    public RepairDraftToolResult createRepairTicket(
        String userId,
        String dormitory,
        String issue
    ) {
        log.info("Tool createRepairTicket called, userId={}", userId);
        // TODO: 对接真实后勤工单系统
        return new RepairDraftToolResult(
            orDefault(userId, "anonymous"),
            orDefault(dormitory, "待补充"),
            orDefault(issue, "待补充"),
            "DRAFT_CREATED",
            "请补充宿舍号和故障详情后提交",
            nowTs()
        );
    }

    private String nowTs() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());
    }

    private static String orDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
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
