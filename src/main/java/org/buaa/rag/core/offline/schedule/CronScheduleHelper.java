package org.buaa.rag.core.offline.schedule;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.StringUtils;

/**
 * Cron 表达式工具类，用于定时调度的参数校验。
 */
public final class CronScheduleHelper {

    private CronScheduleHelper() {
    }

    /**
     * 计算从 {@code from} 开始的下一个触发时刻。
     */
    public static LocalDateTime nextRunTime(String cron, LocalDateTime from) {
        if (!StringUtils.hasText(cron) || from == null) {
            return null;
        }
        return CronExpression.parse(cron.strip()).next(from);
    }

    /**
     * 判断 cron 表达式两次触发之间的间隔是否小于给定秒数。
     * <p>
     * 主要用于校验用户提交的定时表达式是否过于频繁。
     */
    public static boolean isIntervalTooShort(String cron, LocalDateTime from, long minSeconds) {
        if (!StringUtils.hasText(cron) || from == null) {
            return true;
        }
        CronExpression expr = CronExpression.parse(cron.strip());
        LocalDateTime firstTrigger = expr.next(from);
        if (firstTrigger == null) {
            return true;
        }
        LocalDateTime secondTrigger = expr.next(firstTrigger);
        if (secondTrigger == null) {
            return true;
        }
        long gap = Duration.between(firstTrigger, secondTrigger).getSeconds();
        return gap < minSeconds;
    }
}
