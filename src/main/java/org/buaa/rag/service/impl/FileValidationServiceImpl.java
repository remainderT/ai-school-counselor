package org.buaa.rag.service.impl;

import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_TYPE_NOT_SUPPORTED;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.FILE_SIZE_EXCEEDED;

import java.util.Locale;
import java.util.Set;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.service.FileValidationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传校验
 */
@Service
public class FileValidationServiceImpl implements FileValidationService {

    private static final Set<String> ALLOWED_FILE_TYPES = Set.of(
        "pdf", "doc", "docx", "txt", "md", "html", "htm",
        "xls", "xlsx", "ppt", "pptx", "rtf", "csv"
    );

    private static final Set<String> STRICT_MIME_TYPES = Set.of(
        "application/pdf",
        "application/msword",
        "application/rtf",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain",
        "text/csv",
        "text/markdown",
        "text/html"
    );

    @Value("${file.parsing.max-upload-bytes:104857600}")
    private long maxUploadBytes;

    @Override
    public void validate(MultipartFile file, String originalFileName, String detectedMimeType) {
        if (file == null || file.isEmpty() || !StringUtils.hasText(originalFileName)) {
            throw new ClientException(DOCUMENT_TYPE_NOT_SUPPORTED);
        }
        if (file.getSize() > maxUploadBytes) {
            throw new ClientException(FILE_SIZE_EXCEEDED);
        }

        String extension = extractExtension(originalFileName);
        if (!ALLOWED_FILE_TYPES.contains(extension)) {
            throw new ClientException(DOCUMENT_TYPE_NOT_SUPPORTED);
        }

        if (!isMimeAllowed(detectedMimeType, extension)) {
            throw new ClientException("文件类型校验失败，请确认文件格式", DOCUMENT_TYPE_NOT_SUPPORTED);
        }
    }

    private String extractExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isMimeAllowed(String mimeType, String extension) {
        if (!StringUtils.hasText(mimeType)) {
            return false;
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("text/")) {
            return true;
        }
        if (STRICT_MIME_TYPES.contains(normalized)) {
            return true;
        }
        // md/csv 在不同平台可能被识别为 octet-stream，放宽一次兜底
        return ("md".equals(extension) || "csv".equals(extension))
            && "application/octet-stream".equals(normalized);
    }
}
