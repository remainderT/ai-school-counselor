package org.buaa.rag.service.impl;

import org.buaa.rag.service.MinioService;
import org.springframework.stereotype.Service;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;

/**
 * Minio存储接口实现层
 */
@Service
@RequiredArgsConstructor
public class MinioServiceImpl implements MinioService {

    private final MinioClient minioClient;

}
