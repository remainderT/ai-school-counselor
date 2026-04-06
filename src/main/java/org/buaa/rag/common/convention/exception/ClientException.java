package org.buaa.rag.common.convention.exception;

import org.buaa.rag.common.convention.errorcode.BaseErrorCode;
import org.buaa.rag.common.convention.errorcode.IErrorCode;

/**
 * 客户端请求引发的异常（参数校验、权限不足等 A 类错误）。
 */
public class ClientException extends AbstractException {

    public ClientException(String message) {
        super(message, null, BaseErrorCode.CLIENT_ERROR);
    }

    public ClientException(IErrorCode code) {
        super(code.message(), null, code);
    }

    public ClientException(String message, IErrorCode code) {
        super(message, null, code);
    }

    public ClientException(String message, Throwable cause, IErrorCode code) {
        super(message, cause, code);
    }

    @Override
    public String toString() {
        return String.format("ClientException[code=%s, message=%s]", errorCode, errorMessage);
    }
}
