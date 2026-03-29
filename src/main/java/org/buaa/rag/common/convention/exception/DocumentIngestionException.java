package org.buaa.rag.common.convention.exception;

import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.DOCUMENT_UPLOAD_FAILED;

import org.buaa.rag.common.convention.errorcode.IErrorCode;

import lombok.Getter;

/**
 * 文档摄取异步处理异常
 */
@Getter
public class DocumentIngestionException extends ServiceException {

    private final boolean retryable;

    public DocumentIngestionException(String message, boolean retryable) {
        this(message, null, retryable, DOCUMENT_UPLOAD_FAILED);
    }

    public DocumentIngestionException(String message, Throwable throwable, boolean retryable) {
        this(message, throwable, retryable, DOCUMENT_UPLOAD_FAILED);
    }

    public DocumentIngestionException(String message,
                                      Throwable throwable,
                                      boolean retryable,
                                      IErrorCode errorCode) {
        super(message, throwable, errorCode);
        this.retryable = retryable;
    }
}
