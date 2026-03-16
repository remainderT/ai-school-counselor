package org.buaa.rag.module.ingestion;

import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_PARSE_FAILED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_SIZE_EXCEEDED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_TYPE_NOT_SUPPORTED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_UPLOAD_NULL;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.DocumentIngestionException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 文档摄取失败处理
 */
@Component
public class DocumentIngestionFailureResolver {

    private static final int MAX_FAILURE_REASON_LENGTH = 512;

    public DocumentIngestionException toIngestionException(Exception exception) {
        if (exception instanceof DocumentIngestionException ingestionException) {
            return ingestionException;
        }
        boolean retryable = isRetryable(exception);
        if (exception instanceof ServiceException serviceException) {
            return new DocumentIngestionException(
                serviceException.getErrorMessage(),
                serviceException,
                retryable
            );
        }
        return new DocumentIngestionException(
            "文档处理失败: " + exception.getMessage(),
            exception,
            retryable
        );
    }

    public boolean isRetryable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DocumentIngestionException ingestionException) {
                return ingestionException.isRetryable();
            }
            if (current instanceof ClientException) {
                return false;
            }
            if (current instanceof ServiceException serviceException && isNonRetryable(serviceException)) {
                return false;
            }
            current = current.getCause();
        }
        return true;
    }

    public String summarizeFailureReason(Throwable throwable) {
        Throwable root = rootCause(throwable);
        String message = root.getMessage();
        String prefix = root.getClass().getSimpleName();
        String summary = StringUtils.hasText(message) ? prefix + ": " + message.trim() : prefix;
        return normalizeFailureReason(summary);
    }

    public String normalizeFailureReason(String failureReason) {
        if (!StringUtils.hasText(failureReason)) {
            return null;
        }
        String value = failureReason.trim();
        if (value.length() <= MAX_FAILURE_REASON_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    private boolean isNonRetryable(ServiceException serviceException) {
        String errorCode = serviceException.getErrorCode();
        return DOCUMENT_PARSE_FAILED.code().equals(errorCode)
            || DOCUMENT_TYPE_NOT_SUPPORTED.code().equals(errorCode)
            || DOCUMENT_SIZE_EXCEEDED.code().equals(errorCode)
            || DOCUMENT_UPLOAD_NULL.code().equals(errorCode);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable root = throwable;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == null ? new RuntimeException("未知异常") : root;
    }
}
