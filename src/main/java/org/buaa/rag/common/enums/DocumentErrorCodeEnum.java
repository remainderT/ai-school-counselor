package org.buaa.rag.common.enums;

import org.buaa.rag.common.convention.errorcode.IErrorCode;

/**
 * 文档错误码
 */
public enum DocumentErrorCodeEnum implements IErrorCode {

    DOCUMENT_UPLOAD_FAILED("C000101", "文档上传错误"),

    DOCUMENT_MIME_FAILED("C000102", "文档上传错误"),

    DOCUMENT_TYPE_NOT_SUPPORTED("C000103", "不支持的文档格式"),

    DOCUMENT_UPLOAD_NULL("C000104", "文档不能为空"),

    DOCUMENT_PARSE_FAILED("C000105", "文档解析失败"),

    DOCUMENT_SIZE_EXCEEDED("C000106", "文档大小超出限制"),

    DOCUMENT_EXISTS("C000107", "文档已经存在，请勿重复上传"),

    DOCUMENT_NOT_EXISTS("C000201", "文档不存在"),

    DOCUMENT_ACCESS_CONTROL_ERROR("C000202", "文档权限错误");


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
