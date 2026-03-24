package org.buaa.rag.module.ingestion;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 文档离线摄取任务
 */
public record DocumentIngestionTask(Long documentId, int retryCount) {

    public static final String FIELD_DOCUMENT_ID = "documentId";
    public static final String FIELD_RETRY_COUNT = "retryCount";
    public static final String FIELD_ENQUEUED_AT = "enqueuedAt";

    public static DocumentIngestionTask initial(Long documentId) {
        return new DocumentIngestionTask(normalize(documentId), 0);
    }

    public static DocumentIngestionTask from(Map<Object, Object> body) {
        if (body == null || body.isEmpty()) {
            return new DocumentIngestionTask(null, 0);
        }
        return new DocumentIngestionTask(
            parseLongSafely(body.get(FIELD_DOCUMENT_ID)),
            parseIntSafely(body.get(FIELD_RETRY_COUNT))
        );
    }

    public boolean isValid() {
        return documentId != null && documentId > 0;
    }

    public DocumentIngestionTask nextRetry() {
        return new DocumentIngestionTask(documentId, retryCount + 1);
    }

    public Map<String, String> toPayload() {
        if (!isValid()) {
            throw new IllegalArgumentException("文档摄取任务缺少必要字段");
        }
        return Map.of(
            FIELD_DOCUMENT_ID, String.valueOf(documentId),
            FIELD_RETRY_COUNT, String.valueOf(Math.max(0, retryCount)),
            FIELD_ENQUEUED_AT, Instant.now().toString()
        );
    }

    private static Long normalize(Long value) {
        return value == null || value <= 0 ? null : value;
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

    private static Long parseLongSafely(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return normalize(number.longValue());
        }
        try {
            return normalize(Long.parseLong(Objects.toString(value, null)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
