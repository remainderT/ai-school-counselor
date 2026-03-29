package org.buaa.rag.common.enums;

import org.buaa.rag.common.convention.errorcode.IErrorCode;

/**
 * 知识库错误码
 */
public enum OnlineErrorCodeEnum implements IErrorCode {

    MESSAGE_EMPTY("D000101", "消息内容不能为空"),

    MESSAGE_ID_REQUIRED("D000102", "消息ID不能为空"),

    SCORE_OUT_OF_RANGE("D000103", "评分必须在1-5之间"),

    SEARCH_SERVICE_ERROR("D000104", "搜索服务异常");

    private final String code;
    private final String message;

    OnlineErrorCodeEnum(String code, String message) {
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
