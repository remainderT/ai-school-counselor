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
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * RustFS (S3 compatible) 对象存储配置
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StorageConfiguration {

    private final StorageProperties properties;

    @Bean
    public S3Client rustfsS3Client() {
        S3Client storageClient = S3Client.builder()
            .endpointOverride(URI.create(properties.getServiceEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKeyId(), properties.getSecretAccessKey())))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build())
            .region(Region.of(properties.getRegion()))
            .build();
        ensureBucketExists(storageClient);
        return storageClient;
    }

    private void ensureBucketExists(S3Client client) {
        try {
            String bucket = properties.getStorageBucket();
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("存储桶已就绪: {}", bucket);
        } catch (NoSuchBucketException e) {
            createBucket(client);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                createBucket(client);
                return;
            }
            log.error("存储桶初始化失败: {}", e.getMessage(), e);
            throw new RuntimeException("无法初始化对象存储", e);
        } catch (Exception e) {
            log.error("存储桶初始化失败: {}", e.getMessage(), e);
            throw new RuntimeException("无法初始化对象存储", e);
        }
    }

    private void createBucket(S3Client client) {
        String bucket = properties.getStorageBucket();
        client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        log.info("成功创建存储桶: {}", bucket);
    }
}
