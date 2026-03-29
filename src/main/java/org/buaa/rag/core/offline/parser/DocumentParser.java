package org.buaa.rag.core.offline.parser;

import java.io.InputStream;
import java.util.Map;

/**
 * 文档解析器接口
 */
public interface DocumentParser {

    String getParserType();

    default int getPriority() {
        return 100;
    }

    DocumentParseResult parse(InputStream stream,
                              String fileName,
                              String mimeType,
                              Map<String, Object> options) throws Exception;

    default boolean supports(String mimeType, String fileName) {
        return true;
    }
}

