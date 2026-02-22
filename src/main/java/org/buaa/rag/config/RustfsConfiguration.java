package org.buaa.rag.config;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
@Configuration
public class RustfsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RustfsConfiguration.class);

    @Value("${rustfs.endpoint}")
    private String serviceEndpoint;

    @Value("${rustfs.accessKey}")
    private String accessKeyId;

    @Value("${rustfs.secretKey}")
    private String secretAccessKey;

    @Value("${rustfs.bucketName:uploads}")
    private String storageBucket;

    @Value("${rustfs.region:us-east-1}")
    private String region;

    @Bean
    public S3Client rustfsS3Client() {
        S3Client storageClient = S3Client.builder()
            .endpointOverride(URI.create(serviceEndpoint))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build())
            .region(Region.of(region))
            .build();
        ensureBucketExists(storageClient);
        return storageClient;
    }

    private void ensureBucketExists(S3Client client) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(storageBucket).build());
            log.info("存储桶已就绪: {}", storageBucket);
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
        client.createBucket(CreateBucketRequest.builder().bucket(storageBucket).build());
        log.info("成功创建存储桶: {}", storageBucket);
    }
}
