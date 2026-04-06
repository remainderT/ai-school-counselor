package org.buaa.rag.common.convention.exception;

import org.buaa.rag.common.convention.errorcode.IErrorCode;

import lombok.Getter;

/**
 * 业务异常基类，派生出 {@link ClientException} 与 {@link ServiceException} 两类异常。
 */
@Getter
public abstract class AbstractException extends RuntimeException {

    public final String errorCode;

    public final String errorMessage;

    protected AbstractException(String message, Throwable cause, IErrorCode code) {
        super(message, cause);
        this.errorCode = code.code();
        this.errorMessage = (message != null && !message.isEmpty()) ? message : code.message();
    }
}