package org.buaa.rag.core.offline.parser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Markdown / 纯文本解析器
 */
@Component
@RequiredArgsConstructor
public class MarkdownDocumentParser implements DocumentParser {

    private final TextCleaningService textCleaningService;

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
            return DocumentParseResult.ofText(textCleaningService.clean(text, 0));
        }
    }

    @Override
    public boolean supports(String mimeType, String fileName) {
        String normalizedMime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        return normalizedMime.contains("markdown")
            || normalizedMime.startsWith("text/plain");
    }
}
