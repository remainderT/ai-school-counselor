package org.buaa.rag.service.ingestion;

import static org.buaa.rag.common.consts.DocumentIngestionStreamConstants.FIELD_DOCUMENT_MD5;
import static org.buaa.rag.common.consts.DocumentIngestionStreamConstants.FIELD_ENQUEUED_AT;
import static org.buaa.rag.common.consts.DocumentIngestionStreamConstants.FIELD_FILE_NAME;
import static org.buaa.rag.common.consts.DocumentIngestionStreamConstants.FIELD_RETRY_COUNT;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 文档摄取任务生产者
 */
@Component
public class DocumentIngestionStreamProducer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionStreamProducer.class);

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${rag.ingestion.stream.key:rag:stream:document-ingestion}")
    private String streamKey;

    public DocumentIngestionStreamProducer(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public RecordId enqueue(String documentMd5, String fileName, int retryCount) {
        if (!StringUtils.hasText(documentMd5) || !StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("文档任务参数不完整");
        }
        Map<String, String> payload = new HashMap<>();
        payload.put(FIELD_DOCUMENT_MD5, documentMd5.trim());
        payload.put(FIELD_FILE_NAME, fileName.trim());
        payload.put(FIELD_RETRY_COUNT, String.valueOf(Math.max(0, retryCount)));
        payload.put(FIELD_ENQUEUED_AT, Instant.now().toString());

        RecordId recordId = stringRedisTemplate.opsForStream()
            .add(StreamRecords.string(payload).withStreamKey(streamKey));
        log.info("文档摄取任务入队: md5={}, retry={}, record={}", documentMd5, retryCount, recordId);
        return recordId;
    }
}
