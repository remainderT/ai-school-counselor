package org.buaa.rag.config;

import org.buaa.rag.properties.MilvusProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.RequiredArgsConstructor;

/**
 * Milvus 客户端配置
 */
@Configuration
@RequiredArgsConstructor
public class MilvusConfiguration {

    private final MilvusProperties milvusProperties;

    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClient() {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(milvusProperties.getUri())
                .connectTimeoutMs(milvusProperties.getConnectTimeoutMs());
        String token = milvusProperties.getToken();
        if (token != null && !token.isBlank()) {
            builder.token(token);
        }
        return new MilvusClientV2(builder.build());
    }
}
