package org.buaa.rag.tool;

/**
 * 计时工具类：提供纳秒起点到毫秒耗时的转换。
 */
public final class TimingUtils {

    private TimingUtils() {
    }

    /**
     * 将 {@link System#nanoTime()} 起点转换为毫秒耗时。
     *
     * @param startNanos {@code System.nanoTime()} 记录的起点
     * @return 经过的毫秒数
     */
    public static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
