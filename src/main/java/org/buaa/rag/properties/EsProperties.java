package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Elasticsearch 连接与索引配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "elasticsearch")
public class EsProperties {

    /**
     * ES 主机地址
     */
    private String host;

    /**
     * ES 端口号
     */
    private int port = 9200;

    /**
     * 协议（http / https）
     */
    private String scheme = "http";

    /**
     * 索引名称
     */
    private String index = "knowledge";
}
