package org.buaa.rag.core.offline.parser;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 文本清洗实现
 */
@Service
public class TextCleaningService {

    public String clean(String rawText, int maxChars) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }

        String cleaned = rawText
            .replace("\uFEFF", "")
            .replace('\u0000', ' ')
            .replaceAll("[ \\t]+\\n", "\n")
            .replaceAll("\\r\\n", "\n")
            .replaceAll("\\n{3,}", "\n\n")
            .replaceAll("\\uFFFD", " ")
            .replaceAll("[\\t\\x0B\\f]+", " ")
            .replaceAll("[ ]{2,}", " ")
            .trim();

        if (maxChars > 0 && cleaned.length() > maxChars) {
            return cleaned.substring(0, maxChars);
        }
        return cleaned;
    }
}
