package org.buaa.rag.mq;

import java.util.Map;

import org.buaa.rag.module.ingestion.DocumentIngestionTask;
import org.buaa.rag.properties.StreamProperties;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档摄取任务生产者（Redis Stream）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionProducer {

    private final StringRedisTemplate stringRedisTemplate;
    private final StreamProperties properties;

    /**
     * 将文档摄取任务入队
     */
    public RecordId enqueue(DocumentIngestionTask task) {
        Map<String, String> payload = task.toPayload();
        RecordId recordId = stringRedisTemplate.opsForStream()
            .add(StreamRecords.string(payload).withStreamKey(properties.getKey()));
        log.info("文档摄取任务入队: documentId={}, retry={}, record={}", task.documentId(), task.retryCount(), recordId);
        return recordId;
    }
}
