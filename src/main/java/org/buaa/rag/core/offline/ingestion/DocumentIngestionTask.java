package org.buaa.rag.core.offline.ingestion;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.buaa.rag.core.offline.chunk.ChunkingMode;

/**
 * 文档离线摄取任务
 */
public record DocumentIngestionTask(Long documentId, int retryCount, String chunkMode) {

    public static final String FIELD_DOCUMENT_ID = "documentId";
    public static final String FIELD_RETRY_COUNT = "retryCount";
    public static final String FIELD_CHUNK_MODE = "chunkMode";
    public static final String FIELD_ENQUEUED_AT = "enqueuedAt";

    public static DocumentIngestionTask initial(Long documentId, String chunkMode) {
        return new DocumentIngestionTask(documentId, 0, chunkMode);
    }

    public static DocumentIngestionTask from(Map<Object, Object> body) {
        if (body == null || body.isEmpty()) {
            return new DocumentIngestionTask(null, 0, null);
        }
        return new DocumentIngestionTask(
            parseLongSafely(body.get(FIELD_DOCUMENT_ID)),
            parseIntSafely(body.get(FIELD_RETRY_COUNT)),
            normalizeChunkMode(Objects.toString(body.get(FIELD_CHUNK_MODE), null))
        );
    }

    public DocumentIngestionTask nextRetry() {
        return new DocumentIngestionTask(documentId, retryCount + 1, chunkMode);
    }

    public Map<String, String> toPayload() {
        if (documentId == null || documentId <= 0) {
            throw new IllegalArgumentException("文档摄取任务缺少必要字段");
        }
        Map<String, String> payload = new HashMap<>();
        payload.put(FIELD_DOCUMENT_ID, String.valueOf(documentId));
        payload.put(FIELD_RETRY_COUNT, String.valueOf(Math.max(0, retryCount)));
        payload.put(FIELD_ENQUEUED_AT, Instant.now().toString());
        if (chunkMode != null && !chunkMode.isBlank()) {
            payload.put(FIELD_CHUNK_MODE, chunkMode);
        }
        return payload;
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

    private static String normalizeChunkMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return ChunkingMode.resolve(raw).getValue();
    }
}
