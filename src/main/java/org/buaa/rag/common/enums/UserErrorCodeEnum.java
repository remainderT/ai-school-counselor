package org.buaa.rag.common.enums;

import org.buaa.rag.common.convention.errorcode.IErrorCode;

/**
 * 用户错误码
 */
public enum UserErrorCodeEnum implements IErrorCode {

    USER_NULL("A000101", "用户不存在"),

    USER_CODE_ERROR("A000102", "验证码错误"),

    USER_MAIL_EXIST("A000103", "邮箱已被注册"),

    USER_NAME_EXIST("A000104", "用户名已被注册"),

    USER_PASSWORD_ERROR("A000201", "密码错误"),

    USER_REPEATED_LOGIN("A000202", "重复登录"),

    USER_TOKEN_NULL("A000203", "用户未登录"),

    USER_TOKEN_ERROR("A000204", "登陆token错误"),

    USER_UPDATE_ERROR("A000302", "用户信息更新失败");

    private final String code;

    private final String message;

    UserErrorCodeEnum(String code, String message) {
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
