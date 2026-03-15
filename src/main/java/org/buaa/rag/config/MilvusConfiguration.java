package org.buaa.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;

/**
 * Milvus 客户端配置
 */
@Configuration
public class MilvusConfiguration {

    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClient(@Value("${milvus.uri}") String uri,
                                       @Value("${milvus.token:}") String token) {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder().uri(uri);
        if (token != null && !token.isBlank()) {
            builder.token(token);
        }
        return new MilvusClientV2(builder.build());
    }
}
