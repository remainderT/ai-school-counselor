package org.buaa.rag.tool;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@RequiredArgsConstructor
public class RustfsStorage {

    private final S3Client s3Client;

    public void upload(String bucket, String objectPath, InputStream data, long size, String contentType) throws Exception {
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(objectPath)
            .contentType(contentType)
            .build();
        RequestBody body = buildRequestBody(data, size);
        s3Client.putObject(request, body);
    }

    public InputStream download(String bucket, String objectPath) throws Exception {
        return s3Client.getObject(GetObjectRequest.builder()
            .bucket(bucket)
            .key(objectPath)
            .build());
    }

    public void delete(String bucket, String objectPath) throws Exception {
        s3Client.deleteObject(DeleteObjectRequest.builder()
            .bucket(bucket)
            .key(objectPath)
            .build());
    }

    private RequestBody buildRequestBody(InputStream data, long size) throws IOException {
        if (size >= 0) {
            return RequestBody.fromInputStream(data, size);
        }
        return RequestBody.fromBytes(data.readAllBytes());
    }
}
