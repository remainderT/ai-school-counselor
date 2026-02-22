package org.buaa.rag.common.convention.exception;

import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_PARSE_FAILED;

/**
 * 文档摄取异常，标记是否可重试。
 */
public class DocumentIngestionException extends ServiceException {

    private final boolean retryable;

    public DocumentIngestionException(String message, Throwable throwable, boolean retryable) {
        super(message, throwable, DOCUMENT_PARSE_FAILED);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
