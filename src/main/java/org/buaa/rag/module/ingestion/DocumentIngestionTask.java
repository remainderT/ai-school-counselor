package org.buaa.rag.module.ingestion;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.springframework.util.StringUtils;

/**
 * 文档离线摄取任务
 */
public record DocumentIngestionTask(String documentMd5, String originalFileName, int retryCount) {

    public static final String FIELD_DOCUMENT_MD5 = "documentMd5";
    public static final String FIELD_FILE_NAME = "fileName";
    public static final String FIELD_RETRY_COUNT = "retryCount";
    public static final String FIELD_ENQUEUED_AT = "enqueuedAt";

    public static DocumentIngestionTask initial(String documentMd5, String originalFileName) {
        return new DocumentIngestionTask(normalize(documentMd5), normalize(originalFileName), 0);
    }

    public static DocumentIngestionTask from(Map<Object, Object> body) {
        if (body == null || body.isEmpty()) {
            return new DocumentIngestionTask(null, null, 0);
        }
        return new DocumentIngestionTask(
            normalize(Objects.toString(body.get(FIELD_DOCUMENT_MD5), null)),
            normalize(Objects.toString(body.get(FIELD_FILE_NAME), null)),
            parseIntSafely(body.get(FIELD_RETRY_COUNT))
        );
    }

    public boolean isValid() {
        return StringUtils.hasText(documentMd5) && StringUtils.hasText(originalFileName);
    }

    public DocumentIngestionTask nextRetry() {
        return new DocumentIngestionTask(documentMd5, originalFileName, retryCount + 1);
    }

    public Map<String, String> toPayload() {
        if (!isValid()) {
            throw new IllegalArgumentException("文档摄取任务缺少必要字段");
        }
        return Map.of(
            FIELD_DOCUMENT_MD5, documentMd5,
            FIELD_FILE_NAME, originalFileName,
            FIELD_RETRY_COUNT, String.valueOf(Math.max(0, retryCount)),
            FIELD_ENQUEUED_AT, Instant.now().toString()
        );
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static int parseIntSafely(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
