package org.buaa.rag.common.convention.errorcode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局基础错误码。
 * <p>
 * 命名规范：A 客户端 / B 服务端 / C 第三方。
 */
@Getter
@AllArgsConstructor
public enum BaseErrorCode implements IErrorCode {

    CLIENT_ERROR("A000001", "用户端错误"),
    USER_REGISTER_ERROR("A000100", "用户注册错误"),
    USER_LOGIN_ERROR("A000200", "用户登录异常"),
    FLOW_LIMIT_ERROR("A000300", "请求过于频繁"),

    SERVICE_ERROR("B000001", "系统执行出错"),
    SERVICE_TIMEOUT_ERROR("B000100", "系统执行超时"),

    REMOTE_ERROR("C000001", "调用第三方服务出错");

    private final String code;
    private final String message;

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}