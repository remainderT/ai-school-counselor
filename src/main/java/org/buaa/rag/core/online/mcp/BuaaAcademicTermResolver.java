package org.buaa.rag.core.online.mcp;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BuaaAcademicTermResolver {

    private static final Pattern TERM_CODE_PATTERN = Pattern.compile("(20\\d{2})\\s*[-/]\\s*(20\\d{2})\\s*[-/]\\s*([123])");
    private static final Pattern YEAR_SEASON_PATTERN = Pattern.compile("(20\\d{2})\\s*(?:学年)?\\s*(秋季|春季|夏季|秋|春|夏)");
    private static final Pattern WEEK_PATTERN = Pattern.compile("(?:第\\s*)?(\\d{1,2})\\s*周", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEK_EQUALS_PATTERN = Pattern.compile("week\\s*=\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    public String resolveTermCode(String query) {
        if (!StringUtils.hasText(query)) {
            return null;
        }
        String normalized = query.trim();
        Matcher explicit = TERM_CODE_PATTERN.matcher(query);
        if (explicit.find()) {
            return normalizeTermCode(explicit.group(1), explicit.group(2), explicit.group(3));
        }
        Matcher yearSeason = YEAR_SEASON_PATTERN.matcher(query);
        if (yearSeason.find()) {
            int year = Integer.parseInt(yearSeason.group(1));
            String season = yearSeason.group(2);
            if (season.startsWith("秋")) {
                return year + "-" + (year + 1) + "-1";
            }
            if (season.startsWith("夏")) {
                return (year - 1) + "-" + year + "-3";
            }
            return (year - 1) + "-" + year + "-2";
        }
        if (normalized.contains("这学期") || normalized.contains("本学期") || normalized.contains("当前学期")) {
            return currentTermCode();
        }
        if (normalized.contains("上学期")) {
            return shiftTerm(currentTermCode(), -1);
        }
        if (normalized.contains("下学期")) {
            return shiftTerm(currentTermCode(), 1);
        }
        return null;
    }

    public Integer resolveWeek(String query) {
        if (!StringUtils.hasText(query)) {
            return null;
        }
        Matcher matcher = WEEK_PATTERN.matcher(query);
        if (matcher.find()) {
            return clampWeek(matcher.group(1));
        }
        Matcher equalsMatcher = WEEK_EQUALS_PATTERN.matcher(query);
        if (equalsMatcher.find()) {
            return clampWeek(equalsMatcher.group(1));
        }
        return null;
    }

    public String normalizeTermCode(String firstYear, String secondYear, String termPart) {
        if (!StringUtils.hasText(firstYear) || !StringUtils.hasText(secondYear) || !StringUtils.hasText(termPart)) {
            return null;
        }
        int first = Integer.parseInt(firstYear);
        int second = Integer.parseInt(secondYear);
        int term = Integer.parseInt(termPart);
        if (term == 1) {
            return first + "-" + (first + 1) + "-1";
        }
        if (term == 3) {
            return (second - 1) + "-" + second + "-3";
        }
        if (second == first) {
            return (first - 1) + "-" + first + "-2";
        }
        if (second < first) {
            return second + "-" + first + "-2";
        }
        return first + "-" + second + "-2";
    }

    public String describeTerm(String termCode) {
        if (!StringUtils.hasText(termCode)) {
            return "";
        }
        Matcher matcher = TERM_CODE_PATTERN.matcher(termCode.trim());
        if (!matcher.matches()) {
            return termCode.trim();
        }
        int first = Integer.parseInt(matcher.group(1));
        int second = Integer.parseInt(matcher.group(2));
        int term = Integer.parseInt(matcher.group(3));
        if (term == 1) {
            return first + "学年秋季";
        }
        if (term == 3) {
            return second + "学年夏季";
        }
        return second + "学年春季";
    }

    public String currentTermCode() {
        LocalDate now = LocalDate.now(ZONE_ID);
        int year = now.getYear();
        int month = now.getMonthValue();
        if (month >= 9) {
            return year + "-" + (year + 1) + "-1";
        }
        if (month >= 2 && month <= 6) {
            return (year - 1) + "-" + year + "-2";
        }
        return (year - 1) + "-" + year + "-1";
    }

    public String shiftTerm(String termCode, int offset) {
        if (!StringUtils.hasText(termCode) || offset == 0) {
            return termCode;
        }
        Matcher matcher = TERM_CODE_PATTERN.matcher(termCode.trim());
        if (!matcher.matches()) {
            return termCode;
        }
        int first = Integer.parseInt(matcher.group(1));
        int term = Integer.parseInt(matcher.group(3));
        String result = termCode.trim();
        int steps = Math.abs(offset);
        for (int i = 0; i < steps; i++) {
            result = offset > 0 ? nextTerm(result) : previousTerm(result);
        }
        return result;
    }

    private Integer clampWeek(String raw) {
        int week = Integer.parseInt(raw);
        if (week < 1) {
            return 1;
        }
        return Math.min(week, 30);
    }

    private String nextTerm(String termCode) {
        Matcher matcher = TERM_CODE_PATTERN.matcher(termCode.trim());
        if (!matcher.matches()) {
            return termCode;
        }
        int first = Integer.parseInt(matcher.group(1));
        int term = Integer.parseInt(matcher.group(3));
        if (term == 1) {
            return first + "-" + (first + 1) + "-2";
        }
        if (term == 2) {
            return first + "-" + (first + 1) + "-3";
        }
        return (first + 1) + "-" + (first + 2) + "-1";
    }

    private String previousTerm(String termCode) {
        Matcher matcher = TERM_CODE_PATTERN.matcher(termCode.trim());
        if (!matcher.matches()) {
            return termCode;
        }
        int first = Integer.parseInt(matcher.group(1));
        int term = Integer.parseInt(matcher.group(3));
        if (term == 3) {
            return first + "-" + (first + 1) + "-2";
        }
        if (term == 2) {
            return first + "-" + (first + 1) + "-1";
        }
        return (first - 1) + "-" + first + "-3";
    }
}
