package org.buaa.rag.tool;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RustFS/S3 Bucket 生命周期管理器。
 * <p>
 * 每个知识库对应一个独立的 Bucket（名称 = knowledge.name），
 * 在知识库创建/删除时由 KnowledgeServiceImpl 调用本类完成 Bucket 的创建与清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BucketManager {

    private final S3Client s3Client;

    /**
     * 将知识库 name 转换为合法的 RustFS/S3 Bucket 名称：下划线替换为连字符。
     * <p>
     * RustFS 不允许 Bucket 名称含下划线，因此统一在此做规范化处理，
     * 调用方只需传入 knowledge.name 即可，无需自行转换。
     *
     * @param knowledgeName 知识库 name（如 academic-kb）
     * @return 合法 Bucket 名称（如 academic-kb）
     */
    public static String toBucketName(String knowledgeName) {
        if (knowledgeName == null) {
            return "";
        }
        return knowledgeName.replace('_', '-');
    }

    /**
     * 确保指定 Bucket 存在，不存在则创建。
     *
     * @param bucketName 已规范化的 Bucket 名称（使用 {@link #toBucketName} 转换后的值）
     */
    public void ensureBucket(String bucketName) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            log.info("存储桶已就绪: {}", bucketName);
        } catch (NoSuchBucketException e) {
            createBucket(bucketName);
        } catch (S3Exception e) {
            // RustFS 对不存在的 bucket 返回 400 或 404（而非标准的 NoSuchBucket）
            if (e.statusCode() == 404 || e.statusCode() == 400) {
                createBucket(bucketName);
                return;
            }
            log.error("存储桶初始化失败: bucket={}, error={}", bucketName, e.getMessage(), e);
            throw new RuntimeException("无法初始化存储桶: " + bucketName, e);
        } catch (Exception e) {
            log.error("存储桶初始化失败: bucket={}, error={}", bucketName, e.getMessage(), e);
            throw new RuntimeException("无法初始化存储桶: " + bucketName, e);
        }
    }

    /**
     * 删除指定 Bucket 及其所有对象（知识库删除时调用）。
     *
     * @param bucketName 知识库 name
     */
    public void deleteBucket(String bucketName) {
        if (!bucketExists(bucketName)) {
            log.warn("存储桶不存在，跳过删除: {}", bucketName);
            return;
        }
        try {
            // 先清空 Bucket 内所有对象，再删除 Bucket
            deleteAllObjects(bucketName);
            s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build());
            log.info("存储桶已删除: {}", bucketName);
        } catch (Exception e) {
            log.error("存储桶删除失败: bucket={}, error={}", bucketName, e.getMessage(), e);
            throw new RuntimeException("无法删除存储桶: " + bucketName, e);
        }
    }

    private boolean bucketExists(String bucketName) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            return true;
        } catch (NoSuchBucketException e) {
            return false;
        } catch (S3Exception e) {
            // RustFS 对不存在的 bucket 可能返回 400 或 404
            return e.statusCode() != 404 && e.statusCode() != 400;
        }
    }

    private void createBucket(String bucketName) {
        s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
        log.info("成功创建存储桶: {}", bucketName);
    }

    private void deleteAllObjects(String bucketName) {
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder().bucket(bucketName);
            if (continuationToken != null) {
                requestBuilder.continuationToken(continuationToken);
            }
            ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
            List<ObjectIdentifier> toDelete = response.contents().stream()
                .map(obj -> ObjectIdentifier.builder().key(obj.key()).build())
                .collect(Collectors.toList());
            if (!toDelete.isEmpty()) {
                s3Client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(toDelete).build())
                    .build());
                log.info("已清理存储桶对象: bucket={}, count={}", bucketName, toDelete.size());
            }
            continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
    }
}
