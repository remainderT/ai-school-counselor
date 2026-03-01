package org.buaa.rag.core.parser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
            return DocumentParseResult.ofText(TextCleanupUtil.cleanup(text));
        }
    }

    @Override
    public boolean supports(String mimeType, String fileName) {
        String normalizedMime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (normalizedMime.contains("markdown")) {
            return true;
        }
        if (normalizedMime.startsWith("text/plain")) {
            return true;
        }

        if (!StringUtils.hasText(fileName)) {
            return false;
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".md")
            || lowerName.endsWith(".markdown")
            || lowerName.endsWith(".txt");
    }
}

