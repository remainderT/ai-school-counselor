package org.buaa.rag.common.convention.exception;

import org.buaa.rag.common.convention.errorcode.BaseErrorCode;
import org.buaa.rag.common.convention.errorcode.IErrorCode;

/**
 * 服务端执行异常（内部逻辑错误、超时等 B 类错误）。
 */
public class ServiceException extends AbstractException {

    public ServiceException(String message) {
        super(message, null, BaseErrorCode.SERVICE_ERROR);
    }

    public ServiceException(IErrorCode code) {
        super(code.message(), null, code);
    }

    public ServiceException(String message, IErrorCode code) {
        super(message, null, code);
    }

    public ServiceException(String message, Throwable cause, IErrorCode code) {
        super(message != null ? message : code.message(), cause, code);
    }

    @Override
    public String toString() {
        return String.format("ServiceException[code=%s, message=%s]", errorCode, errorMessage);
    }
}