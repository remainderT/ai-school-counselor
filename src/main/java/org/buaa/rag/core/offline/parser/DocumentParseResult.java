package org.buaa.rag.core.offline.parser;

import java.util.Map;

/**
 * 文档解析器产出的结构化结果，包含纯文本和可选元数据。
 *
 * @param text     解析后的纯文本内容
 * @param metadata 从文档中提取的元数据（标题、作者等）
 */
public record DocumentParseResult(String text, Map<String, Object> metadata) {

    /** 仅含文本的快捷构造 */
    public static DocumentParseResult textOnly(String text) {
        return new DocumentParseResult(text != null ? text : "", Map.of());
    }

    /** 含文本和元数据的完整构造 */
    public static DocumentParseResult withMetadata(String text, Map<String, Object> metadata) {
        return new DocumentParseResult(
            text != null ? text : "",
            metadata != null ? metadata : Map.of()
        );
    }
}
