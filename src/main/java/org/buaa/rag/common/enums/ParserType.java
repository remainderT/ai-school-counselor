package org.buaa.rag.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文档解析器类型标识。
 */
@Getter
@AllArgsConstructor
public enum ParserType {

    TIKA("tika"),
    MARKDOWN("markdown");

    private final String type;
}
