package org.buaa.rag.config;

import java.net.URI;

import org.buaa.rag.properties.StorageProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * RustFS (S3 compatible) 对象存储配置。
 * <p>
 * Bucket 的生命周期（创建/删除）由 {@code BucketManager} 在知识库创建/删除时按需管理，
 * 此处仅负责构建 S3Client Bean。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StorageConfiguration {

    private final StorageProperties properties;

    @Bean
    public S3Client rustfsS3Client() {
        return S3Client.builder()
            .endpointOverride(URI.create(properties.getServiceEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKeyId(), properties.getSecretAccessKey())))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build())
            .region(Region.of(properties.getRegion()))
            .build();
    }
}
