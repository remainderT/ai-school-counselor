package org.buaa.rag.tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.buaa.rag.core.online.tool.mcp.BuaaAcademicTermResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AcademicAffairsTools {

    private static final Map<Integer, String> DAY_OF_WEEK_LABELS = Map.of(
        1, "周一",
        2, "周二",
        3, "周三",
        4, "周四",
        5, "周五",
        6, "周六",
        7, "周日"
    );

    private final BuaaJwClient buaaJwClient;
    private final BuaaAcademicTermResolver termResolver;

    public String queryScoresByTerm(String termCode) {
        if (!StringUtils.hasText(termCode)) {
            return missingTermMessage("成绩");
        }
        String normalizedTermCode = termResolver.resolveTermCode(termCode);
        if (!StringUtils.hasText(normalizedTermCode)) {
            normalizedTermCode = termCode.trim();
        }
        JsonNode data = buaaJwClient.queryScores(normalizedTermCode).path("datas");
        if (!data.isArray() || data.isEmpty()) {
            return "未查询到 " + termResolver.describeTerm(normalizedTermCode) + " 的成绩数据。";
        }

        int passed = 0;
        int numericCount = 0;
        double totalScore = 0D;
        double weightedScore = 0D;
        double totalCredit = 0D;
        List<String> details = new ArrayList<>();

        for (JsonNode item : data) {
            String courseName = item.path("courseName").asText("");
            String scoreDisplay = item.path("scoreDisplay").asText("");
            double score = item.path("score").asDouble(Double.NaN);
            double credit = item.path("credit").asDouble(0D);
            if (item.path("passStatus").asBoolean(false)) {
                passed++;
            }
            if (!Double.isNaN(score)) {
                numericCount++;
                totalScore += score;
                weightedScore += score * credit;
            }
            totalCredit += credit;
            details.add("- " + courseName
                + "｜成绩 " + scoreDisplay
                + "｜" + trimDecimal(credit) + " 学分"
                + "｜" + item.path("courseNature").asText("")
                + "｜" + item.path("courseType").asText(""));
        }

        StringBuilder builder = new StringBuilder();
        builder.append("已查询到 ").append(termResolver.describeTerm(normalizedTermCode))
            .append("（").append(normalizedTermCode).append("）成绩。\n");
        builder.append("共 ").append(data.size()).append(" 门，已通过 ").append(passed).append(" 门，累计 ")
            .append(trimDecimal(totalCredit)).append(" 学分");
        if (numericCount > 0) {
            builder.append("，均分 ").append(formatOneDecimal(totalScore / numericCount));
            if (totalCredit > 0) {
                builder.append("，学分加权 ").append(formatOneDecimal(weightedScore / totalCredit));
            }
        }
        builder.append("。\n");
        builder.append(String.join("\n", details));
        return builder.toString();
    }

    public String queryScheduleByWeek(String termCode, Integer week) {
        if (!StringUtils.hasText(termCode)) {
            return missingTermMessage("课表");
        }
        int resolvedWeek = week == null || week < 1 ? 1 : week;
        String normalizedTermCode = termResolver.resolveTermCode(termCode);
        if (!StringUtils.hasText(normalizedTermCode)) {
            normalizedTermCode = termCode.trim();
        }
        JsonNode datas = buaaJwClient.querySchedule(normalizedTermCode, resolvedWeek).path("datas");
        JsonNode arrangedList = datas.path("arrangedList");
        JsonNode notArrangeList = datas.path("notArrangeList");
        String studentName = datas.path("name").asText("");

        StringBuilder builder = new StringBuilder();
        builder.append("已查询到 ").append(termResolver.describeTerm(normalizedTermCode))
            .append(" 第 ").append(resolvedWeek).append(" 周课表");
        if (StringUtils.hasText(studentName)) {
            builder.append("，学生：").append(studentName);
        }
        builder.append("。\n");

        if (arrangedList.isArray() && !arrangedList.isEmpty()) {
            Map<Integer, List<JsonNode>> grouped = new LinkedHashMap<>();
            arrangedList.forEach(item -> grouped.computeIfAbsent(item.path("dayOfWeek").asInt(0), ignored -> new ArrayList<>()).add(item));
            grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String dayLabel = DAY_OF_WEEK_LABELS.getOrDefault(entry.getKey(), "未标注日期");
                    builder.append(dayLabel).append("：\n");
                    entry.getValue().stream()
                        .sorted(Comparator.comparingInt(item -> item.path("beginSection").asInt(0)))
                        .forEach(item -> builder.append("- ")
                            .append(item.path("beginTime").asText("")).append("-")
                            .append(item.path("endTime").asText("")).append("｜第")
                            .append(item.path("beginSection").asText("")).append("-")
                            .append(item.path("endSection").asText("")).append("节｜")
                            .append(item.path("courseName").asText(""))
                            .append("｜").append(item.path("placeName").asText("待定"))
                            .append("｜").append(item.path("weeksAndTeachers").asText("")).append("\n"));
                });
        } else {
            builder.append("本周暂无已排课课程。\n");
        }

        if (notArrangeList.isArray() && !notArrangeList.isEmpty()) {
            builder.append("未排定具体时间的课程：\n");
            notArrangeList.forEach(item -> builder.append("- ")
                .append(item.path("courseName").asText(""))
                .append("｜").append(item.path("weeksAndTeachers").asText("")).append("\n"));
        }
        return builder.toString().trim();
    }

    private String missingTermMessage(String scene) {
        return "请补充要查询的学期，例如 `2021-2022-1`（2021学年秋季）或 `2022-2023-2`（2023学年春季）。"
            + " 也可以直接说“这学期”“上学期”“下学期”或“2022学年春季”，系统会先换算成标准学期编码后查询" + scene + "。";
    }

    private String trimDecimal(double value) {
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return formatOneDecimal(value);
    }

    private String formatOneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
