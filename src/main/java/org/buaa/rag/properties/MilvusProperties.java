package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Milvus 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "milvus")
public class MilvusProperties {

    /**
     * Milvus 服务地址
     */
    private String uri = "http://localhost:19530";

    /**
     * 可选鉴权令牌
     */
    private String token;

    /**
     * 向量相似度度量
     */
    private String metricType = "COSINE";

    /**
     * HNSW 索引 M 参数
     */
    private int indexM = 48;

    /**
     * HNSW 建索引 efConstruction 参数
     */
    private int efConstruction = 200;

    /**
     * 检索时 ef 参数
     */
    private int searchEf = 128;

    /**
     * 文本字段最大长度
     */
    private int maxTextLength = 65535;
}
