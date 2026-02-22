package org.buaa.rag.tool;

import java.io.IOException;
import java.io.InputStream;

import org.buaa.rag.properties.StorageProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class RustfsStorage {

    private final S3Client s3Client;

    private final StorageProperties properties;

    private String getBucket() {
        return properties.getStorageBucket();
    }

    public void upload(String objectPath, InputStream data,
                       long size, String contentType) throws Exception {
        Assert.hasText(objectPath, "objectPath 不能为空");
        Assert.notNull(data, "上传数据流不能为 null");


        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(getBucket())
            .key(objectPath)
            .contentType(contentType)
            .build();
        RequestBody body = buildRequestBody(data, size);
        s3Client.putObject(request, body);

        log.info("文件上传完成: bucket={}, path={}", getBucket(), objectPath);
    }

    public InputStream download(String objectPath) throws Exception {
        Assert.hasText(objectPath, "objectPath 不能为空");

        InputStream stream = s3Client.getObject(GetObjectRequest.builder()
            .bucket(getBucket())
            .key(objectPath)
            .build());

        log.info("文件下载完成: bucket={}, path={}", getBucket(), objectPath);
        return stream;
    }

    public void delete(String objectPath) throws Exception {
        Assert.hasText(objectPath, "objectPath 不能为空");

        s3Client.deleteObject(DeleteObjectRequest.builder()
            .bucket(getBucket())
            .key(objectPath)
            .build());

        log.info("文件删除完成: bucket={}, path={}", getBucket(), objectPath);
    }

    private RequestBody buildRequestBody(InputStream data, long size) throws IOException {
        if (size >= 0) {
            return RequestBody.fromInputStream(data, size);
        }
        log.debug("文件大小未知,回退为全量读取字节流");
        return RequestBody.fromBytes(data.readAllBytes());
    }
}
