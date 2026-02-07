package org.buaa.rag.common.enums;

import org.buaa.rag.common.convention.errorcode.IErrorCode;

/**
 * 文档错误码
 */
public enum DocumentErrorCodeEnum implements IErrorCode {

    DOCUMENT_NULL("C000101", "文档不存在"),

    DOCUMENT_ACCESS_CONTROL_ERROR("C000102", "文档权限错误"),

    DOCUMENT_TYPE_NOT_SUPPORTED("C000103", "不支持的文档格式");


    private final String code;

    private final String message;

    DocumentErrorCodeEnum(String code, String message) {
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
