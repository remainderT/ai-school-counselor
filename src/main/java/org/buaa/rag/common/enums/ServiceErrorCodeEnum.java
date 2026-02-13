package org.buaa.rag.common.enums;

import org.buaa.rag.common.convention.errorcode.IErrorCode;

/**
 * 系统错误码
 */
public enum ServiceErrorCodeEnum implements IErrorCode {

    MAIL_SEND_ERROR("B000101", "邮件发送错误"),

    IMAGE_UPLOAD_ERROR("B000102", "图片上传错误"),

    FLOW_LIMIT_ERROR("B000103", "当前系统繁忙，请稍后再试"),

    PARAM_INVALID("A0102", "参数格式错误"),

    MESSAGE_EMPTY("A0103", "消息内容不能为空"),

    QUERY_EMPTY("A0104", "搜索关键词不能为空"),

    MESSAGE_ID_REQUIRED("A0105", "消息ID不能为空"),

    SCORE_OUT_OF_RANGE("A0106", "评分必须在1-5之间"),

    FILE_TYPE_NOT_SUPPORTED("A0201", "不支持的文件格式"),

    FILE_UPLOAD_FAILED("A0202", "文件上传失败"),

    FILE_PARSE_FAILED("A0203", "文件解析失败"),

    FILE_SIZE_EXCEEDED("A0204", "文件大小超出限制"),

    FILE_ALREADY_EXISTS("A0205", "文件已存在"),

    FILE_ACCESS_DENIED("A0301", "无权访问该文件"),

    OPERATION_FORBIDDEN("A0302", "操作被禁止"),

    DOCUMENT_NOT_FOUND("A0401", "文档不存在"),

    CONVERSATION_NOT_FOUND("A0402", "对话不存在"),

    RETRIEVAL_FAILED("A0403", "检索失败"),

    CHAT_SERVICE_ERROR("B0101", "对话服务异常"),

    SEARCH_SERVICE_ERROR("B0102", "搜索服务异常"),

    EMBEDDING_SERVICE_ERROR("B0103", "向量化服务异常"),

    LLM_SERVICE_ERROR("B0104", "大模型服务异常"),

    STORAGE_SERVICE_ERROR("B0105", "存储服务异常"),

    DATABASE_ERROR("B0106", "数据库操作异常"),

    ELASTICSEARCH_ERROR("C0101", "Elasticsearch服务异常"),

    MINIO_ERROR("C0102", "MinIO服务异常"),

    DEEPSEEK_API_ERROR("C0103", "DeepSeek API异常"),

    EMBEDDING_API_ERROR("C0104", "向量化API异常");

    private final String code;

    private final String message;

    ServiceErrorCodeEnum(String code, String message) {
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
