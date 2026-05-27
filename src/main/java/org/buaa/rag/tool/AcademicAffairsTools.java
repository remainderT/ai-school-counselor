package org.buaa.rag.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.buaa.rag.core.online.mcp.BuaaAcademicTermResolver;
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
    private final org.buaa.rag.properties.BuaaJwProperties buaaJwProperties;

    public String queryScores(String termCode, String courseName) {
        String normalizedCourseName = normalizeKeyword(courseName);
        if (StringUtils.hasText(termCode)) {
            return queryScoresSingleTerm(termCode, normalizedCourseName);
        }
        List<TermCalendar> termCalendars = listAvailableTerms();
        if (termCalendars.isEmpty()) {
            return "暂时无法获取可查询学期列表，请稍后重试。";
        }
        return queryScoresAcrossTerms(termCalendars, normalizedCourseName);
    }

    public String queryScoresByTerm(String termCode) {
        return queryScores(termCode, null);
    }

    public String queryScheduleByWeek(String termCode, Integer week) {
        List<TermCalendar> termCalendars = resolveTargetTerms(termCode);
        if (termCalendars.isEmpty()) {
            return missingTermMessage("课表");
        }
        return queryScheduleSingleTerm(termCalendars.get(0).termCode(), week);
    }

    public String queryExams(String termCode, String courseName) {
        String normalizedCourseName = normalizeKeyword(courseName);
        if (StringUtils.hasText(termCode)) {
            return queryExamsSingleTerm(termCode, normalizedCourseName);
        }
        List<TermCalendar> termCalendars = listAvailableTerms();
        if (termCalendars.isEmpty()) {
            return "暂时无法获取可查询学期列表，请稍后重试。";
        }
        return queryExamsAcrossTerms(termCalendars, normalizedCourseName);
    }

    private String queryScoresSingleTerm(String termCode, String courseName) {
        String normalizedTermCode = termResolver.resolveTermCode(termCode);
        if (!StringUtils.hasText(normalizedTermCode)) {
            normalizedTermCode = termCode.trim();
        }
        JsonNode data = buaaJwClient.queryScores(normalizedTermCode).path("datas");
        if (StringUtils.hasText(courseName)) {
            data = filterByCourseName(data, courseName);
        }
        if (!data.isArray() || data.isEmpty()) {
            if (StringUtils.hasText(courseName)) {
                return "未查询到 " + termResolver.describeTerm(normalizedTermCode) + " 中课程《" + courseName + "》的成绩数据。";
            }
            return "未查询到 " + termResolver.describeTerm(normalizedTermCode) + " 的成绩数据。";
        }

        int passed = 0;
        int numericCount = 0;
        double totalScore = 0D;
        double weightedScore = 0D;
        double totalCredit = 0D;
        List<String> details = new ArrayList<>();

        for (JsonNode item : data) {
            String currentCourseName = item.path("courseName").asText("");
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
            details.add("- " + currentCourseName
                + "｜成绩 " + scoreDisplay
                + "｜" + trimDecimal(credit) + " 学分"
                + "｜" + item.path("courseNature").asText("")
                + "｜" + item.path("courseType").asText(""));
        }

        StringBuilder builder = new StringBuilder();
        builder.append("已查询到 ").append(termResolver.describeTerm(normalizedTermCode))
            .append("（").append(normalizedTermCode).append("）");
        if (StringUtils.hasText(courseName)) {
            builder.append("中课程《").append(courseName).append("》");
        }
        builder.append("成绩。\n");
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

    private String queryScoresAcrossTerms(List<TermCalendar> termCalendars, String courseName) {
        List<String> sections = new ArrayList<>();
        int hitTerms = 0;
        for (TermCalendar calendar : termCalendars) {
            JsonNode data = buaaJwClient.queryScores(calendar.termCode()).path("datas");
            if (StringUtils.hasText(courseName)) {
                data = filterByCourseName(data, courseName);
            }
            if (!data.isArray() || data.isEmpty()) {
                continue;
            }
            hitTerms++;
            sections.add(buildScoreTermSection(calendar.termCode(), data, courseName));
        }
        if (sections.isEmpty()) {
            if (StringUtils.hasText(courseName)) {
                return "已遍历全部学期，但未查询到课程《" + courseName + "》的成绩记录。";
            }
            return "已遍历全部学期，但未查询到任何成绩记录。";
        }
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(courseName)) {
            builder.append("已遍历 ").append(termCalendars.size()).append(" 个学期，查询到课程《")
                .append(courseName).append("》在 ").append(hitTerms).append(" 个学期有成绩记录。\n");
        } else {
            builder.append("已遍历 ").append(termCalendars.size()).append(" 个学期，查询到 ")
                .append(hitTerms).append(" 个学期有成绩记录。\n");
        }
        builder.append(String.join("\n\n", sections));
        return builder.toString();
    }

    private String buildScoreTermSection(String termCode, JsonNode data, String courseName) {
        int passed = 0;
        int numericCount = 0;
        double totalScore = 0D;
        double weightedScore = 0D;
        double totalCredit = 0D;
        List<String> details = new ArrayList<>();

        for (JsonNode item : data) {
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
            details.add("- " + item.path("courseName").asText("")
                + "｜成绩 " + scoreDisplay
                + "｜" + trimDecimal(credit) + " 学分"
                + "｜" + item.path("courseNature").asText("")
                + "｜" + item.path("courseType").asText(""));
        }

        StringBuilder builder = new StringBuilder();
        builder.append(termResolver.describeTerm(termCode)).append("（").append(termCode).append("）");
        if (StringUtils.hasText(courseName)) {
            builder.append("课程《").append(courseName).append("》");
        }
        builder.append("：共 ").append(data.size()).append(" 门，已通过 ").append(passed).append(" 门，累计 ")
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

    private String queryScheduleSingleTerm(String termCode, Integer week) {
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
        builder.append("\n如需查看最新课表变更，可前往教务系统：")
            .append(buaaJwProperties.getPortalUrl());
        return builder.toString().trim();
    }

    private String queryExamsSingleTerm(String termCode, String courseName) {
        String normalizedTermCode = termResolver.resolveTermCode(termCode);
        if (!StringUtils.hasText(normalizedTermCode)) {
            normalizedTermCode = termCode.trim();
        }
        JsonNode data = buaaJwClient.queryExams(normalizedTermCode).path("datas");
        if (StringUtils.hasText(courseName)) {
            data = filterByCourseName(data, courseName);
        }
        if (!data.isArray() || data.isEmpty()) {
            if (StringUtils.hasText(courseName)) {
                return "未查询到 " + termResolver.describeTerm(normalizedTermCode) + " 中课程《" + courseName + "》的考试安排。";
            }
            return "未查询到 " + termResolver.describeTerm(normalizedTermCode) + " 的考试安排。";
        }
        return buildExamTermSection(normalizedTermCode, data, courseName, true);
    }

    private String queryExamsAcrossTerms(List<TermCalendar> termCalendars, String courseName) {
        List<String> sections = new ArrayList<>();
        int hitTerms = 0;
        for (TermCalendar calendar : termCalendars) {
            JsonNode data = buaaJwClient.queryExams(calendar.termCode()).path("datas");
            if (StringUtils.hasText(courseName)) {
                data = filterByCourseName(data, courseName);
            }
            if (!data.isArray() || data.isEmpty()) {
                continue;
            }
            hitTerms++;
            sections.add(buildExamTermSection(calendar.termCode(), data, courseName, false));
        }
        if (sections.isEmpty()) {
            if (StringUtils.hasText(courseName)) {
                return "已遍历全部学期，但未查询到课程《" + courseName + "》的考试安排。";
            }
            return "已遍历全部学期，但未查询到任何考试安排。";
        }
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(courseName)) {
            builder.append("已遍历 ").append(termCalendars.size()).append(" 个学期，查询到课程《")
                .append(courseName).append("》在 ").append(hitTerms).append(" 个学期有考试安排。\n");
        } else {
            builder.append("已遍历 ").append(termCalendars.size()).append(" 个学期，查询到 ")
                .append(hitTerms).append(" 个学期有考试安排。\n");
        }
        builder.append(String.join("\n\n", sections));
        return builder.toString();
    }

    private String buildExamTermSection(String termCode, JsonNode data, String courseName, boolean leadingIntro) {
        List<String> details = new ArrayList<>();
        for (JsonNode item : data) {
            details.add("- " + item.path("courseName").asText("")
                + "｜" + item.path("examTimeDescription").asText("时间待定")
                + "｜" + item.path("examPlace").asText("地点待定")
                + "｜座位号 " + item.path("examSeatNo").asText("待定"));
        }
        StringBuilder builder = new StringBuilder();
        if (leadingIntro) {
            builder.append("已查询到 ").append(termResolver.describeTerm(termCode))
                .append("（").append(termCode).append("）");
            if (StringUtils.hasText(courseName)) {
                builder.append("中课程《").append(courseName).append("》");
            }
            builder.append("的考试安排，共 ").append(data.size()).append(" 条。\n");
        } else {
            builder.append(termResolver.describeTerm(termCode)).append("（").append(termCode).append("）");
            if (StringUtils.hasText(courseName)) {
                builder.append("课程《").append(courseName).append("》");
            }
            builder.append("：共 ").append(data.size()).append(" 条考试安排。\n");
        }
        builder.append(String.join("\n", details));
        return builder.toString();
    }

    private String missingTermMessage(String scene) {
        return "请补充要查询的学期，例如 `2021-2022-1`（2021学年秋季）或 `2022-2023-2`（2023学年春季）。"
            + " 也可以直接说“这学期”“上学期”“下学期”或“2022学年春季”，系统会先换算成标准学期编码后查询" + scene + "。"
            + " 如果你不指定学期，我也可以自动遍历全部学期帮你查。";
    }

    private List<TermCalendar> resolveTargetTerms(String termCode) {
        if (!StringUtils.hasText(termCode)) {
            List<TermCalendar> calendars = listAvailableTerms();
            if (calendars.isEmpty()) {
                return List.of();
            }
            return calendars.stream()
                .filter(TermCalendar::selected)
                .findFirst()
                .map(List::of)
                .orElseGet(() -> List.of(calendars.get(0)));
        }
        String normalizedTermCode = termResolver.resolveTermCode(termCode);
        if (!StringUtils.hasText(normalizedTermCode)) {
            normalizedTermCode = termCode.trim();
        }
        return List.of(new TermCalendar(normalizedTermCode, termResolver.describeTerm(normalizedTermCode), true));
    }

    private List<TermCalendar> listAvailableTerms() {
        JsonNode data = buaaJwClient.querySchoolCalendars().path("datas");
        if (!data.isArray() || data.isEmpty()) {
            return List.of();
        }
        List<TermCalendar> calendars = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : data) {
            String termCode = item.path("itemCode").asText("");
            if (!StringUtils.hasText(termCode) || !seen.add(termCode)) {
                continue;
            }
            calendars.add(new TermCalendar(
                termCode,
                item.path("itemName").asText(termResolver.describeTerm(termCode)),
                item.path("selected").asBoolean(false)
            ));
        }
        calendars.sort((left, right) -> Integer.compare(extractTermIndex(right.termCode()), extractTermIndex(left.termCode())));
        return Collections.unmodifiableList(calendars);
    }

    private int extractTermIndex(String termCode) {
        if (!StringUtils.hasText(termCode)) {
            return Integer.MIN_VALUE;
        }
        String[] parts = termCode.trim().split("-");
        if (parts.length != 3) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(parts[0]) * 10 + Integer.parseInt(parts[2]);
        } catch (NumberFormatException ignore) {
            return Integer.MIN_VALUE;
        }
    }

    private JsonNode filterByCourseName(JsonNode data, String courseName) {
        if (!StringUtils.hasText(courseName) || !data.isArray()) {
            return data;
        }
        List<JsonNode> matched = new ArrayList<>();
        String keyword = courseName.trim().replaceAll("\\s+", "");
        data.forEach(item -> {
            String current = item.path("courseName").asText("").replaceAll("\\s+", "");
            if (StringUtils.hasText(current) && current.contains(keyword)) {
                matched.add(item);
            }
        });
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().addAll(matched);
    }

    private String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        return keyword.trim();
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

    private record TermCalendar(String termCode, String termName, boolean selected) {
    }
}
