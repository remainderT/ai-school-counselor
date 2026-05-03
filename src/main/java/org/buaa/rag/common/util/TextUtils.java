package org.buaa.rag.common.util;

/**
 * 文本处理通用工具类。
 * <p>
 * 整合项目中多处重复出现的 compact、truncate、elapsedMs 等方法，消除代码冗余。
 */
public final class TextUtils {

    private TextUtils() {
    }

    /**
     * 将文本归一化（去换行/合并空白）并截断到指定最大长度。
     * 多个 core 模块中的 compact() 逻辑统一到此处。
     *
     * @param text   原始文本
     * @param maxLen 最大长度（超出部分用 "..." 替代）
     * @return 归一化并截断后的文本，null 返回空字符串
     */
    public static String compact(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxLen - 3)) + "...";
    }

    /**
     * 截断文本到指定长度，超出部分用省略号替代。
     * 不做换行归一化，适用于已经是单行的文本。
     *
     * @param text   原始文本
     * @param maxLen 最大长度
     * @return 截断后的文本
     */
    public static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, Math.max(1, maxLen - 3)) + "...";
    }

    /**
     * 计算从 startNanos 到当前时刻的耗时（毫秒）。
     * 多个模块中的 elapsedMs() 统一到此处。
     *
     * @param startNanos System.nanoTime() 采样值
     * @return 耗时毫秒数
     */
    public static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
