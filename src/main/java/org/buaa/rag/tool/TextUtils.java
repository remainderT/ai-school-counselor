package org.buaa.rag.tool;

/**
 * 文本处理工具类：提供日志截断、空白判断等通用方法。
 */
public final class TextUtils {

    private TextUtils() {
    }

    /**
     * 将文本压缩为单行、去掉多余空白，超过 maxLength 则截断并加省略号。
     *
     * @param text      原始文本，可为 null
     * @param maxLength 最大保留长度
     * @return 压缩后的字符串，null 输入返回空串
     */
    public static String compact(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) + "..." : normalized;
    }

    /** compact 的默认 120 字符版本 */
    public static String compact(String text) {
        return compact(text, 120);
    }

    /**
     * 判断字符串是否为空或仅含空白字符。
     */
    public static boolean isBlank(String str) {
        return str == null || str.isBlank();
    }
}
