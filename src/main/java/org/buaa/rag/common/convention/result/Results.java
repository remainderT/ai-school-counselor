package org.buaa.rag.common.convention.result;

import org.buaa.rag.common.enums.BaseErrorCode;
import org.buaa.rag.common.convention.exception.AbstractException;

/**
 * {@link Result} 的静态工厂，简化 Controller 层返回值构造。
 */
public final class Results {

    private Results() {
    }

    public static Result<Void> success() {
        return new Result<Void>().setCode(Result.SUCCESS_CODE);
    }

    public static <T> Result<T> success(T data) {
        return new Result<T>().setCode(Result.SUCCESS_CODE).setData(data);
    }

    public static Result<Void> failure() {
        return ofError(BaseErrorCode.SERVICE_ERROR.code(), BaseErrorCode.SERVICE_ERROR.message());
    }

    public static Result<Void> failure(AbstractException ex) {
        String code = ex.getErrorCode() != null ? ex.getErrorCode() : BaseErrorCode.SERVICE_ERROR.code();
        String msg = ex.getErrorMessage() != null ? ex.getErrorMessage() : BaseErrorCode.SERVICE_ERROR.message();
        return ofError(code, msg);
    }

    public static Result<Void> failure(String errorCode, String errorMessage) {
        return ofError(errorCode, errorMessage);
    }

    private static Result<Void> ofError(String code, String message) {
        return new Result<Void>().setCode(code).setMessage(message);
    }
}
