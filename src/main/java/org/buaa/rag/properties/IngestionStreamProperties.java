package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 文档摄取 Redis Stream 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "ingestion.stream")
public class IngestionStreamProperties {

    /** 是否启用消费者 */
    private boolean enabled = true;

    /** Stream key */
    private String key = "rag:stream:document-ingestion";

    /** 消费者组名称 */
    private String group = "document-ingestion-group";

    /** 消费者名称 */
    private String consumer = "document-worker-1";

    /** 每次拉取的消息数 */
    private int batchSize = 5;

    /** 阻塞读取超时（毫秒） */
    private long blockMs = 1500;

    /** 轮询间隔（毫秒） */
    private long pollIntervalMs = 500;

    /** 最大重试次数 */
    private int maxRetries = 3;
}
