package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 多通道检索配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.search")
public class SearchChannelProperties {

    private Channels channels = new Channels();
    private PostProcessor postProcessor = new PostProcessor();

    @Data
    public static class Channels {
        private IntentDirected intentDirected = new IntentDirected();
        private VectorGlobal vectorGlobal = new VectorGlobal();
    }

    @Data
    public static class IntentDirected {
        /**
         * 是否启用意图定向检索通道
         */
        private boolean enabled = true;
        /**
         * 最低意图置信度
         */
        private double minIntentScore = 0.55;
        /**
         * 定向召回倍数
         */
        private int topKMultiplier = 2;
    }

    @Data
    public static class VectorGlobal {
        /**
         * 是否启用全局向量兜底通道
         */
        private boolean enabled = true;
        /**
         * 当意图置信度低于该值，启用向量全局检索
         */
        private double confidenceThreshold = 0.65;
        /**
         * 全局召回倍数
         */
        private int topKMultiplier = 2;
        /**
         * 无意图信息时的默认通道置信度
         */
        private double defaultConfidence = 0.7;
    }

    @Data
    public static class PostProcessor {
        /**
         * 是否启用去重处理
         */
        private boolean deduplicate = true;
        /**
         * 是否启用重排处理
         */
        private boolean rerank = true;
    }
}
