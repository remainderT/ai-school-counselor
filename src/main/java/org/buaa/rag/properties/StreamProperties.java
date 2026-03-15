package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 文档摄取 Redis Stream 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "stream")
public class StreamProperties {

    /**
     * 是否启用 Redis Stream 异步摄取
     */
    private boolean enabled = true;

    /**
     * Stream Key
     */
    private String key = "rag:document:ingestion";

    /**
     * 消费组名称
     */
    private String group = "document-ingestion-group";

    /**
     * 消费者名称
     */
    private String consumer = "document-ingestion-consumer";

    /**
     * 单批次拉取数量
     */
    private int batchSize = 5;

    /**
     * 新消息阻塞读取时长
     */
    private long blockMs = 1000L;

    /**
     * 轮询间隔
     */
    private long pollIntervalMs = 500L;

    /**
     * 最大重试次数
     */
    private int maxRetries = 1;
}
