package org.buaa.rag.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件类型检测服务
 */
public interface ContentTypeDetectionService {

    /**
     * 基于文件内容检测 MIME 类型
     */
    String detect(MultipartFile file, String originalFileName);
}
