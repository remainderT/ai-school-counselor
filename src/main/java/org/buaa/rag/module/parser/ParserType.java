package org.buaa.rag.module.parser;

/**
 * 解析器类型
 */
public enum ParserType {

    TIKA("tika"),
    MARKDOWN("markdown");

    private final String type;

    ParserType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }
}

