package org.buaa.rag.common.enums;

import org.buaa.rag.common.convention.errorcode.IErrorCode;

/**
 * 系统错误码
 */

public enum ServiceErrorCodeEnum implements IErrorCode {

    MAIL_SEND_ERROR("B000101", "邮件发送错误"),

    FLOW_LIMIT_ERROR("B000102", "当前系统繁忙，请稍后再试"),

    EMBEDDING_SERVICE_ERROR("B000103", "向量化服务异常"),

    STORAGE_SERVICE_ERROR("B000104", "存储服务异常");

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
