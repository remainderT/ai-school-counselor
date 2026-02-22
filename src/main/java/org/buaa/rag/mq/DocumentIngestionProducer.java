package org.buaa.rag.mq;

import java.time.Instant;
import java.util.Map;

import org.buaa.rag.properties.IngestionStreamProperties;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档摄取任务生产者（Redis Stream）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIngestionProducer {

    private final StringRedisTemplate stringRedisTemplate;
    private final IngestionStreamProperties properties;
    public static final String FIELD_DOCUMENT_MD5 = "documentMd5";
    public static final String FIELD_FILE_NAME = "fileName";
    public static final String FIELD_RETRY_COUNT = "retryCount";
    public static final String FIELD_ENQUEUED_AT = "enqueuedAt";

    /**
     * 将文档摄取任务入队
     *
     * @param documentMd5 文档 MD5
     * @param fileName    原始文件名
     * @param retryCount  当前重试次数
     * @return Stream RecordId
     */
    public RecordId enqueue(String documentMd5, String fileName, int retryCount) {
        Assert.hasText(documentMd5, "documentMd5 不能为空");
        Assert.hasText(fileName, "fileName 不能为空");

        Map<String, String> payload = Map.of(
            FIELD_DOCUMENT_MD5, documentMd5.trim(),
            FIELD_FILE_NAME, fileName.trim(),
            FIELD_RETRY_COUNT, String.valueOf(Math.max(0, retryCount)),
            FIELD_ENQUEUED_AT, Instant.now().toString()
        );

        RecordId recordId = stringRedisTemplate.opsForStream()
            .add(StreamRecords.string(payload).withStreamKey(properties.getKey()));
        log.info("文档摄取任务入队: md5={}, retry={}, record={}", documentMd5, retryCount, recordId);
        return recordId;
    }
}
