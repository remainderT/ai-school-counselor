package org.buaa.rag.module.parser;

import java.util.Map;

/**
 * 文档解析结果
 */
public record DocumentParseResult(String text, Map<String, Object> metadata) {

    public static DocumentParseResult ofText(String text) {
        return new DocumentParseResult(text == null ? "" : text, Map.of());
    }

    public static DocumentParseResult of(String text, Map<String, Object> metadata) {
        return new DocumentParseResult(text == null ? "" : text, metadata == null ? Map.of() : metadata);
    }
}

