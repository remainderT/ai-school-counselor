package org.buaa.rag.tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 辅导员工具集合
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounselorTools {

    private final McpWeatherClient mcpWeatherClient;

    public GradeToolResult queryGrade(
        String studentId
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

    public LeaveDraftToolResult createLeaveDraft(
        String userId,
        String startTime,
        String endTime,
        String reason
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

    public RepairDraftToolResult createRepairTicket(
        String userId,
        String dormitory,
        String issue
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

    public WeatherToolResult queryWeatherByMcp(
        String city,
        String queryType,
        Integer days,
        String userId,
        String rawQuery
    ) {
        String resolvedCity = resolveCity(city, rawQuery);
        if (!StringUtils.hasText(resolvedCity)) {
            return new WeatherToolResult(
                null,
                normalizeQueryType(queryType),
                normalizeDays(days),
                "NEED_CITY",
                "请补充需要查询的城市名称，例如：北京、上海。",
                "none",
                nowTs()
            );
        }

        McpWeatherClient.WeatherResponse response = mcpWeatherClient.queryWeather(
            resolvedCity,
            normalizeQueryType(queryType),
            normalizeDays(days),
            safeValue(userId, "anonymous"),
            rawQuery
        );
        if (response == null || !StringUtils.hasText(response.text())) {
            return new WeatherToolResult(
                resolvedCity,
                normalizeQueryType(queryType),
                normalizeDays(days),
                "UNAVAILABLE",
                "天气服务暂不可用，请稍后重试。",
                "none",
                nowTs()
            );
        }

        return new WeatherToolResult(
            resolvedCity,
            normalizeQueryType(queryType),
            normalizeDays(days),
            "SUCCESS",
            response.text(),
            response.source(),
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

    private String resolveCity(String city, String rawQuery) {
        if (StringUtils.hasText(city)) {
            String normalized = city.trim();
            if (normalized.endsWith("市") && normalized.length() > 1) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        }
        return mcpWeatherClient.resolveCityFromText(rawQuery);
    }

    private String normalizeQueryType(String queryType) {
        if (!StringUtils.hasText(queryType)) {
            return "current";
        }
        String normalized = queryType.trim().toLowerCase(Locale.ROOT);
        return "forecast".equals(normalized) ? "forecast" : "current";
    }

    private int normalizeDays(Integer days) {
        if (days == null) {
            return 3;
        }
        return Math.max(1, Math.min(7, days));
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

    public record WeatherToolResult(
        String city,
        String queryType,
        int days,
        String status,
        String summary,
        String source,
        String generatedAt
    ) {
    }
}
