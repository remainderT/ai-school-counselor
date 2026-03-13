package org.buaa.rag.module.parser;

/**
 * 解析后文本清理工具
 */
public final class TextCleanupUtil {

    private TextCleanupUtil() {
    }

    public static String cleanup(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return text
            .replace("\uFEFF", "")
            .replace('\u0000', ' ')
            .replaceAll("[ \\t]+\\n", "\n")
            .replaceAll("\\r\\n", "\n")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }
}

