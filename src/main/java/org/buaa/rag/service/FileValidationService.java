package org.buaa.rag.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传校验服务
 */
public interface FileValidationService {

    /**
     * 校验上传文件
     */
    void validate(MultipartFile file, String originalFileName, String detectedMimeType);
}
