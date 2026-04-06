package org.buaa.rag.core.offline.parser;

import java.io.InputStream;
import java.util.Map;

/**
 * 文档解析器接口。
 * <p>
 * 每种解析器负责特定格式的文件文本提取，通过 {@link #supports} 声明自身能力。
 */
public interface DocumentParser {

    /** 解析器标识（与 {@link ParserType} 对应） */
    String getParserType();

    /** 选择优先级，数值越小越优先被选中 */
    default int getPriority() {
        return 100;
    }

    /** 从输入流中提取文本内容和元数据 */
    DocumentParseResult parse(InputStream stream,
                              String fileName,
                              String mimeType,
                              Map<String, Object> options) throws Exception;

    /** 判断本解析器是否支持给定的 mimeType 或文件名 */
    default boolean supports(String mimeType, String fileName) {
        return true;
    }
}
