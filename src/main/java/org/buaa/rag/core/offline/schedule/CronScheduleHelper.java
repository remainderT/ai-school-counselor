package org.buaa.rag.core.offline.schedule;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.StringUtils;

public final class CronScheduleHelper {

    private CronScheduleHelper() {
    }

    public static LocalDateTime nextRunTime(String cron, LocalDateTime from) {
        if (!StringUtils.hasText(cron) || from == null) {
            return null;
        }
        CronExpression expression = CronExpression.parse(cron.trim());
        return expression.next(from);
    }

    public static boolean isIntervalLessThan(String cron, LocalDateTime from, long minSeconds) {
        if (!StringUtils.hasText(cron) || from == null) {
            return true;
        }
        CronExpression expression = CronExpression.parse(cron.trim());
        LocalDateTime first = expression.next(from);
        if (first == null) {
            return true;
        }
        LocalDateTime second = expression.next(first);
        if (second == null) {
            return true;
        }
        return Duration.between(first, second).getSeconds() < minSeconds;
    }
}
