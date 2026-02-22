package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Rustfs 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    private String serviceEndpoint;

    private String accessKeyId;

    private String secretAccessKey;

    private String storageBucket;

    private String region;
}
