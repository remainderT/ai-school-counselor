package org.buaa.rag.tool;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import org.buaa.rag.core.model.UploadPayload;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * RustFS/S3 对象存储操作工具。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RustfsStorage {

    private final S3Client s3Client;

    /**
     * 上传文件到指定 Bucket。
     *
     * @param bucketName 知识库对应的 Bucket 名称
     * @param payload    上传载体
     */
    public void upload(String bucketName, UploadPayload payload) throws Exception {
        Assert.hasText(bucketName, "bucketName 不能为空");
        String objectPath = buildPrimaryPath(payload.md5(), payload.originalFilename());
        InputStream data = payload.source().getInputStream();
        String contentType = StringUtils.hasText(payload.mimeType()) ? payload.mimeType() : null;

        Assert.hasText(objectPath, "objectPath 不能为空");
        Assert.notNull(data, "上传数据流不能为 null");

        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(objectPath)
            .contentType(contentType)
            .build();
        RequestBody body = buildRequestBody(data, payload.size());
        s3Client.putObject(request, body);

        log.info("文件上传完成: bucket={}, path={}", bucketName, objectPath);
    }

    /**
     * 从指定 Bucket 下载文件。
     *
     * @param bucketName 知识库对应的 Bucket 名称
     * @param objectPath 对象路径
     */
    public InputStream download(String bucketName, String objectPath) throws Exception {
        Assert.hasText(bucketName, "bucketName 不能为空");
        Assert.hasText(objectPath, "objectPath 不能为空");

        InputStream stream = s3Client.getObject(GetObjectRequest.builder()
            .bucket(bucketName)
            .key(objectPath)
            .build());

        log.info("文件下载完成: bucket={}, path={}", bucketName, objectPath);
        return stream;
    }

    public InputStream downloadPrimary(String bucketName, String md5, String filename) throws Exception {
        Assert.hasText(bucketName, "bucketName 不能为空");
        String primaryPath = buildPrimaryPath(md5, filename);
        try {
            return download(bucketName, primaryPath);
        } catch (NoSuchKeyException ex) {
            String legacyPath = buildLegacyPrimaryPath(md5, filename);
            if (primaryPath.equals(legacyPath)) {
                throw ex;
            }
            log.warn("主路径不存在，回退旧路径下载: bucket={}, primaryPath={}, legacyPath={}", bucketName, primaryPath, legacyPath);
            return download(bucketName, legacyPath);
        }
    }

    /**
     * 从指定 Bucket 删除文件。
     *
     * @param bucketName 知识库对应的 Bucket 名称
     * @param md5        文件 MD5
     * @param filename   原始文件名
     */
    public void delete(String bucketName, String md5, String filename) {
        Assert.hasText(bucketName, "bucketName 不能为空");
        deleteQuietly(bucketName, buildPrimaryPath(md5, filename));
        String legacyPath = buildLegacyPrimaryPath(md5, filename);
        if (!buildPrimaryPath(md5, filename).equals(legacyPath)) {
            deleteQuietly(bucketName, legacyPath);
        }
    }

    public String buildPrimaryPath(String md5, String filename) {
        String extension = extractExtension(filename);
        String suffix = StringUtils.hasText(extension) ? "." + extension : "";
        return String.format("%s/source%s", md5, suffix);
    }

    public String buildLegacyPrimaryPath(String md5, String filename) {
        String extension = extractExtension(filename);
        String suffix = StringUtils.hasText(extension) ? "." + extension : "";
        return String.format("uploads/%s/source%s", md5, suffix);
    }

    private void deleteQuietly(String bucketName, String path) {
        Assert.hasText(path, "path 不能为空");
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(path)
                .build());
            log.info("文件删除完成: bucket={}, path={}", bucketName, path);
        } catch (Exception e) {
            log.warn("文件删除失败: bucket={}, path={}", bucketName, path);
        }
    }

    private RequestBody buildRequestBody(InputStream data, long size) throws IOException {
        if (size >= 0) {
            return RequestBody.fromInputStream(data, size);
        }
        log.debug("文件大小未知，回退为全量读取字节流");
        return RequestBody.fromBytes(data.readAllBytes());
    }

    private String extractExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
