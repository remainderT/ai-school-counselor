package org.buaa.rag.common.enums;

import org.buaa.rag.common.convention.errorcode.IErrorCode;

/**
 * 知识库错误码
 */
public enum KnowledgeErrorCodeEnum implements IErrorCode {

    KNOWLEDGE_ID_REQUIRED("C000300", "请先选择知识库"),
    KNOWLEDGE_NAME_EMPTY("C000301", "知识库名称不能为空"),
    KNOWLEDGE_NOT_EXISTS("C000302", "知识库不存在"),
    KNOWLEDGE_ACCESS_DENIED("C000303", "无权限操作该知识库"),
    KNOWLEDGE_NAME_DUPLICATE("C000304", "知识库名称已存在"),
    KNOWLEDGE_HAS_DOCUMENTS("C000305", "知识库下仍有文档，无法删除");

    private final String code;
    private final String message;

    KnowledgeErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
