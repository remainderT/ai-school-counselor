package org.buaa.rag.service.ingestion;

import static org.buaa.rag.common.consts.DocumentIngestionStreamConstants.FIELD_DOCUMENT_MD5;
import static org.buaa.rag.common.consts.DocumentIngestionStreamConstants.FIELD_FILE_NAME;
import static org.buaa.rag.common.consts.DocumentIngestionStreamConstants.FIELD_RETRY_COUNT;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.buaa.rag.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;

/**
 * 文档摄取任务消费者（Redis Stream）
 */
@Component
@ConditionalOnProperty(prefix = "rag.ingestion.stream", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentIngestionStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionStreamConsumer.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final DocumentService documentService;
    private final DocumentIngestionStreamProducer producer;

    @Value("${rag.ingestion.stream.key:rag:stream:document-ingestion}")
    private String streamKey;

    @Value("${rag.ingestion.stream.group:document-ingestion-group}")
    private String groupName;

    @Value("${rag.ingestion.stream.consumer:document-worker-1}")
    private String consumerName;

    @Value("${rag.ingestion.stream.batch-size:5}")
    private int batchSize;

    @Value("${rag.ingestion.stream.block-ms:1500}")
    private long blockMs;

    @Value("${rag.ingestion.stream.max-retries:3}")
    private int maxRetries;

    public DocumentIngestionStreamConsumer(StringRedisTemplate stringRedisTemplate,
                                           DocumentService documentService,
                                           DocumentIngestionStreamProducer producer) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.documentService = documentService;
        this.producer = producer;
    }

    @PostConstruct
    public void prepareConsumerGroup() {
        ensureGroupReady();
    }

    @Scheduled(fixedDelayString = "${rag.ingestion.stream.poll-interval-ms:500}")
    public void consume() {
        consumePendingRecords();
        consumeNewRecords();
    }

    private void consumePendingRecords() {
        StreamReadOptions options = StreamReadOptions.empty().count(Math.max(1, batchSize));
        List<MapRecord<String, Object, Object>> pending = stringRedisTemplate.opsForStream().read(
            Consumer.from(groupName, consumerName),
            options,
            StreamOffset.create(streamKey, ReadOffset.from("0"))
        );
        consumeRecords(pending);
    }

    private void consumeNewRecords() {
        StreamReadOptions options = StreamReadOptions.empty()
            .count(Math.max(1, batchSize))
            .block(Duration.ofMillis(Math.max(0, blockMs)));
        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
            Consumer.from(groupName, consumerName),
            options,
            StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );
        consumeRecords(records);
    }

    private void consumeRecords(List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            try {
                handleRecord(record);
            } catch (Exception e) {
                log.error("处理文档摄取任务异常: recordId={}", record.getId(), e);
                acknowledge(record);
            }
        }
    }

    private void handleRecord(MapRecord<String, Object, Object> record) {
        Map<Object, Object> values = record.getValue();
        String documentMd5 = asText(values.get(FIELD_DOCUMENT_MD5));
        String fileName = asText(values.get(FIELD_FILE_NAME));
        int retryCount = parseInt(values.get(FIELD_RETRY_COUNT));

        if (!StringUtils.hasText(documentMd5) || !StringUtils.hasText(fileName)) {
            log.debug("忽略无效文档任务: recordId={}", record.getId());
            acknowledge(record);
            return;
        }

        try {
            documentService.ingestDocumentTask(documentMd5, fileName);
            acknowledge(record);
        } catch (Exception ex) {
            if (retryCount < maxRetries) {
                producer.enqueue(documentMd5, fileName, retryCount + 1);
                log.warn("文档摄取失败，已重试入队: md5={}, retry={}/{}",
                    documentMd5, retryCount + 1, maxRetries, ex);
            } else {
                log.error("文档摄取失败，达到最大重试次数: md5={}, retries={}",
                    documentMd5, retryCount, ex);
            }
            acknowledge(record);
        }
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        stringRedisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
        stringRedisTemplate.opsForStream().delete(streamKey, record.getId());
    }

    private void ensureGroupReady() {
        try {
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(streamKey))) {
                stringRedisTemplate.opsForStream()
                    .add(StreamRecords.string(Map.of("bootstrap", "1")).withStreamKey(streamKey));
            }
            stringRedisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), groupName);
            log.info("文档摄取消费者组已创建: stream={}, group={}", streamKey, groupName);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                log.info("文档摄取消费者组已存在: stream={}, group={}", streamKey, groupName);
                return;
            }
            log.warn("创建文档摄取消费者组失败: stream={}, group={}, err={}",
                streamKey, groupName, message);
        }
    }

    private String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private int parseInt(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
