package org.buaa.rag.mq;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.buaa.rag.module.ingestion.DocumentIngestionFailureResolver;
import org.buaa.rag.module.ingestion.DocumentIngestionTask;
import org.buaa.rag.module.ingestion.DocumentIngestionWorkflow;
import org.buaa.rag.properties.StreamProperties;
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
import org.springframework.util.CollectionUtils;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档摄取任务消费者（Redis Stream）
 * <p>
 * 通过定时轮询拉取待处理消息，先消费 pending 列表中的历史消息，再消费新消息。
 * 处理失败时自动重试入队，达到最大重试次数后放弃并记录日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stream", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IngestionConsumer {

    private final StringRedisTemplate redisTemplate;
    private final DocumentIngestionWorkflow ingestionWorkflow;
    private final DocumentIngestionFailureResolver failureResolver;
    private final IngestionProducer producer;
    private final StreamProperties props;
    // ─────────────────── 生命周期 ───────────────────

    @PostConstruct
    public void init() {
        ensureConsumerGroupExists();
    }

    // ─────────────────── 消费循环 ───────────────────

    @Scheduled(fixedDelayString = "${stream.poll-interval-ms:500}")
    public void poll() {
        drainPending();
        drainNew();
    }

    // ─────────────────── 内部实现 ───────────────────

    /**
     * 消费 pending 列表中上次未 ACK 的消息
     */
    private void drainPending() {
        StreamReadOptions opts = StreamReadOptions.empty().count(safeBatchSize());
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
            consumer(), opts, StreamOffset.create(props.getKey(), ReadOffset.from("0"))
        );
        processAll(records);
    }

    /**
     * 阻塞拉取新消息
     */
    private void drainNew() {
        StreamReadOptions opts = StreamReadOptions.empty()
            .count(safeBatchSize())
            .block(Duration.ofMillis(Math.max(0, props.getBlockMs())));
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
            consumer(), opts, StreamOffset.create(props.getKey(), ReadOffset.lastConsumed())
        );
        processAll(records);
    }

    private void processAll(List<MapRecord<String, Object, Object>> records) {
        if (CollectionUtils.isEmpty(records)) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            try {
                if (handleRecord(record)) {
                    acknowledge(record);
                }
            } catch (Exception e) {
                log.error("处理文档摄取任务异常: recordId={}", record.getId(), e);
            }
        }
    }


    private boolean handleRecord(MapRecord<String, Object, Object> record) {
        DocumentIngestionTask task = DocumentIngestionTask.from(record.getValue());
        if (!task.isValid()) {
            log.debug("忽略无效文档任务: recordId={}", record.getId());
            return true;
        }

        try {
            ingestionWorkflow.process(task);
            return true;
        } catch (Exception ex) {
            return handleFailure(task, ex);
        }
    }

    private boolean handleFailure(DocumentIngestionTask task, Exception ex) {
        boolean retryable = failureResolver.isRetryable(ex);
        if (retryable && task.retryCount() < props.getMaxRetries()) {
            DocumentIngestionTask retryTask = task.nextRetry();
            producer.enqueue(retryTask);
            log.warn("文档摄取失败，已重试入队: md5={}, retry={}/{}", task.documentMd5(),
                retryTask.retryCount(), props.getMaxRetries(), ex);
            return true;
        } else {
            ingestionWorkflow.markFailed(task.documentMd5(), failureResolver.summarizeFailureReason(ex));
            if (retryable) {
                log.error("文档摄取失败，达到最大重试次数: md5={}, retries={}", task.documentMd5(), task.retryCount(), ex);
            } else {
                log.error("文档摄取失败，不可重试: md5={}, retries={}", task.documentMd5(), task.retryCount(), ex);
            }
            return true;
        }
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        var ops = redisTemplate.opsForStream();
        ops.acknowledge(props.getKey(), props.getGroup(), record.getId());
        ops.delete(props.getKey(), record.getId());
    }

    // ─────────────────── 初始化 ───────────────────

    private void ensureConsumerGroupExists() {
        try {
            // 确保 Stream 存在
            if (Boolean.FALSE.equals(redisTemplate.hasKey(props.getKey()))) {
                redisTemplate.opsForStream()
                    .add(StreamRecords.string(Map.of("bootstrap", "1")).withStreamKey(props.getKey()));
            }
            redisTemplate.opsForStream().createGroup(props.getKey(), ReadOffset.latest(), props.getGroup());
            log.info("文档摄取消费者组已创建: stream={}, group={}", props.getKey(), props.getGroup());
        } catch (Exception e) {
            if (isBusyGroupException(e)) {
                log.info("文档摄取消费者组已存在: stream={}, group={}", props.getKey(), props.getGroup());
            } else {
                log.warn("创建文档摄取消费者组失败: stream={}, group={}", props.getKey(), props.getGroup(), e);
            }
        }
    }

    /**
     * 递归检查异常链中是否包含 BUSYGROUP 错误（消费者组已存在）
     */
    private static boolean isBusyGroupException(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && msg.contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // ─────────────────── 工具方法 ───────────────────

    private Consumer consumer() {
        return Consumer.from(props.getGroup(), props.getConsumer());
    }

    private int safeBatchSize() {
        return Math.max(1, props.getBatchSize());
    }
}
