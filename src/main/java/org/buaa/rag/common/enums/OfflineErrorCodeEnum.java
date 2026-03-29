package org.buaa.rag.common.enums;

import org.buaa.rag.common.convention.errorcode.IErrorCode;

/**
 * 文档错误码
 */
public enum OfflineErrorCodeEnum implements IErrorCode {

    DOCUMENT_UPLOAD_FAILED("C000101", "文档上传错误"),

    DOCUMENT_MIME_FAILED("C000102", "文档上传错误"),

    DOCUMENT_TYPE_NOT_SUPPORTED("C000103", "不支持的文档格式"),

    DOCUMENT_UPLOAD_NULL("C000104", "文档不能为空"),

    DOCUMENT_PARSE_FAILED("C000105", "文档解析失败"),

    DOCUMENT_SIZE_EXCEEDED("C000106", "文档大小超出限制"),

    DOCUMENT_EXISTS("C000107", "文档已经存在，请勿重复上传"),

    DOCUMENT_URL_INVALID("C000108", "文档地址不合法"),

    DOCUMENT_NAME_NULL("C000109", "文档名不能为空"),

    DOCUMENT_NOT_EXISTS("C000110", "文档不存在"),

    DOCUMENT_ACCESS_CONTROL_ERROR("C000111", "文档权限错误"),

    KNOWLEDGE_ID_REQUIRED("C000201", "请先选择知识库"),

    KNOWLEDGE_NAME_EMPTY("C000203", "知识库名称不能为空"),

    KNOWLEDGE_NOT_EXISTS("C000203", "知识库不存在"),

    KNOWLEDGE_ACCESS_DENIED("C000204", "无权限操作该知识库"),

    KNOWLEDGE_NAME_DUPLICATE("C000205", "知识库名称已存在"),

    KNOWLEDGE_HAS_DOCUMENTS("C000206", "知识库下仍有文档，无法删除");

    private final String code;

    private final String message;

    OfflineErrorCodeEnum(String code, String message) {
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
