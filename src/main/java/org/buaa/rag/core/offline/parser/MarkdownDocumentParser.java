package org.buaa.rag.core.offline.parser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.buaa.rag.common.enums.ParserType;
import org.springframework.stereotype.Component;

/**
 * Markdown / 纯文本解析器
 */
@Component
public class MarkdownDocumentParser implements DocumentParser {


    @Override
    public String getParserType() {
        return ParserType.MARKDOWN.getType();
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public DocumentParseResult parse(InputStream stream,
                                     String fileName,
                                     String mimeType,
                                     Map<String, Object> options) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String text = reader.lines().collect(Collectors.joining("\n"));
            return DocumentParseResult.textOnly(text);
        }
    }

    @Override
    public boolean supports(String mimeType, String fileName) {
        // 优先按文件扩展名判断，避免 mimeType 为 null 时无法命中
        if (fileName != null) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")) {
                return true;
            }
        }
        String normalizedMime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        return normalizedMime.contains("markdown")
            || normalizedMime.startsWith("text/plain");
    }
}
