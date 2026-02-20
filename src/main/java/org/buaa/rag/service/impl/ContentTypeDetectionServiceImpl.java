package org.buaa.rag.service.impl;

import java.io.IOException;
import java.io.InputStream;

import org.apache.tika.Tika;
import org.buaa.rag.service.ContentTypeDetectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;

/**
 * 基于 Tika 的 MIME 检测
 */
@Service
public class ContentTypeDetectionServiceImpl implements ContentTypeDetectionService {

    private static final Logger log = LoggerFactory.getLogger(ContentTypeDetectionServiceImpl.class);

    private final Tika tika = new Tika();

    @Override
    public String detect(MultipartFile file, String originalFileName) {
        if (file == null || file.isEmpty()) {
            return "";
        }
        try (InputStream inputStream = file.getInputStream()) {
            String mimeType = tika.detect(inputStream, originalFileName);
            return mimeType == null ? "" : mimeType.trim();
        } catch (IOException e) {
            log.warn("MIME 检测失败: {}", e.getMessage());
            String fallback = file.getContentType();
            return StringUtils.hasText(fallback) ? fallback.trim() : "";
        }
    }
}
