package org.buaa.rag.module.parser;

import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_MIME_FAILED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_PARSE_FAILED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_TYPE_NOT_SUPPORTED;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.apache.tika.Tika;
import org.springframework.core.io.InputStreamSource;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

public class FileTypeValidate {

    private static final int MAGIC_HEADER_BYTES = 16;

    private static final Tika tika = new Tika();

    /**
     * 单一来源：每个扩展名允许的 MIME 集合
     */
    private static final Map<String, Set<String>> EXTENSION_MIME_MAPPING = createExtensionMimeMapping();

    private static final Set<String> TEXT_LIKE_EXTENSIONS = Set.of("txt", "md", "html", "htm", "csv");

    private static final byte[] PDF_MAGIC = new byte[] {0x25, 0x50, 0x44, 0x46, 0x2D}; // %PDF-
    private static final byte[] OLE_MAGIC = new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] ZIP_MAGIC = new byte[] {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] ZIP_MAGIC_EMPTY = new byte[] {0x50, 0x4B, 0x05, 0x06};
    private static final byte[] ZIP_MAGIC_SPANNED = new byte[] {0x50, 0x4B, 0x07, 0x08};
    private static final byte[] RTF_MAGIC = new byte[] {0x7B, 0x5C, 0x72, 0x74, 0x66}; // {\rtf
    private static final byte[] UTF8_BOM = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16_LE_BOM = new byte[] {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF16_BE_BOM = new byte[] {(byte) 0xFE, (byte) 0xFF};

    public record InspectedFile(String fileName, String mimeType) {
    }

    public static InspectedFile inspectLocal(MultipartFile file) {
        String fileName = requireFilename(file.getOriginalFilename());
        String mimeType = detectMimeType(file, fileName, null);
        validateSource(file, fileName, mimeType);
        return new InspectedFile(fileName, mimeType);
    }

    public static InspectedFile inspectRemote(InputStreamSource source,
                                              String suggestedFileName,
                                              String fallbackMimeType,
                                              String defaultBaseName) {
        String normalizedFileName = normalizeRemoteFileName(suggestedFileName);
        String detectionName = StringUtils.hasText(normalizedFileName) ? normalizedFileName : defaultBaseName;
        String mimeType = detectMimeType(source, detectionName, fallbackMimeType);
        String resolvedFileName = resolveRemoteFilename(normalizedFileName, mimeType, defaultBaseName);
        validateSource(source, resolvedFileName, mimeType);
        return new InspectedFile(resolvedFileName, mimeType);
    }

    public static void validate(String originalFileName, String detectedMimeType, byte[] fileHeader) {
        String extension = extractExtension(originalFileName);
        if (!isSupportedExtension(extension)) {
            throw new ClientException(DOCUMENT_TYPE_NOT_SUPPORTED);
        }

        if (!isMimeAllowed(detectedMimeType, extension)) {
            throw new ClientException("文件类型校验失败，请确认文件格式", DOCUMENT_TYPE_NOT_SUPPORTED);
        }
        if (!isMagicNumberAllowed(extension, fileHeader)) {
            throw new ClientException("文件头校验失败，请确认文件内容与扩展名一致", DOCUMENT_TYPE_NOT_SUPPORTED);
        }
    }

    public static String resolveRemoteFilename(String suggestedFileName, String mimeType, String defaultBaseName) {
        String normalizedFileName = normalizeRemoteFileName(suggestedFileName);
        String resolvedExtension = resolveExtensionFromMimeType(mimeType);
        String fallbackBaseName = StringUtils.hasText(defaultBaseName) ? defaultBaseName.trim() : "remote-document";

        if (StringUtils.hasText(normalizedFileName)) {
            String currentExtension = extractExtension(normalizedFileName);
            if (StringUtils.hasText(currentExtension) && isSupportedExtension(currentExtension)) {
                return normalizedFileName;
            }
            if (StringUtils.hasText(resolvedExtension)) {
                String baseName = stripExtension(normalizedFileName, fallbackBaseName);
                return requireFilename(baseName + "." + resolvedExtension);
            }
            return requireFilename(normalizedFileName);
        }

        if (StringUtils.hasText(resolvedExtension)) {
            return fallbackBaseName + "." + resolvedExtension;
        }
        throw new ClientException("无法识别 URL 文档类型", DOCUMENT_TYPE_NOT_SUPPORTED);
    }

    public static String normalizeRemoteFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String normalized = fileName.trim();
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    public static String normalizeMimeType(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return null;
        }
        int separator = mimeType.indexOf(';');
        String normalized = separator >= 0 ? mimeType.substring(0, separator) : mimeType;
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    public static String extractExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isSupportedExtension(String extension) {
        return StringUtils.hasText(extension)
                && EXTENSION_MIME_MAPPING.containsKey(extension.trim().toLowerCase(Locale.ROOT));
    }

    public static String resolveExtensionFromMimeType(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return null;
        }
        String normalized = normalizeMimeType(mimeType);
        for (Map.Entry<String, Set<String>> entry : EXTENSION_MIME_MAPPING.entrySet()) {
            if (entry.getValue().contains(normalized)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static boolean isMimeAllowed(String mimeType, String extension) {
        if (!StringUtils.hasText(mimeType)) {
            return false;
        }
        String normalized = normalizeMimeType(mimeType);
        if (normalized.startsWith("text/")) {
            return true;
        }
        Set<String> allowedMimes = EXTENSION_MIME_MAPPING.getOrDefault(extension, Set.of());
        if (allowedMimes.contains(normalized)) {
            return true;
        }
        // md/csv 在不同平台可能被识别为 octet-stream，放宽一次兜底
        return ("md".equals(extension) || "csv".equals(extension))
                && "application/octet-stream".equals(normalized);
    }

    private static boolean isMagicNumberAllowed(String extension, byte[] fileHeader) {
        if (fileHeader == null || fileHeader.length == 0) {
            return false;
        }
        return switch (extension) {
            case "pdf" -> startsWith(fileHeader, PDF_MAGIC);
            case "doc", "xls", "ppt" -> startsWith(fileHeader, OLE_MAGIC);
            case "docx", "xlsx", "pptx" ->
                    startsWith(fileHeader, ZIP_MAGIC)
                    || startsWith(fileHeader, ZIP_MAGIC_EMPTY)
                    || startsWith(fileHeader, ZIP_MAGIC_SPANNED);
            case "rtf" -> startsWith(fileHeader, RTF_MAGIC);
            default -> TEXT_LIKE_EXTENSIONS.contains(extension) && looksLikeText(fileHeader);
        };
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeText(byte[] header) {
        if (startsWith(header, UTF8_BOM) || startsWith(header, UTF16_LE_BOM) || startsWith(header, UTF16_BE_BOM)) {
            return true;
        }
        int printable = 0;
        for (byte value : header) {
            int current = value & 0xFF;
            if (current == 0) {
                return false;
            }
            if (current == 9 || current == 10 || current == 13 || current >= 32) {
                printable++;
            }
        }
        return printable >= Math.max(1, (header.length * 7) / 10);
    }

    private static String detectMimeType(InputStreamSource source,
                                         String originalFileName,
                                         String fallbackMimeType) {
        try (InputStream inputStream = source.getInputStream()) {
            String detectedMimeType = normalizeMimeType(tika.detect(inputStream, originalFileName));
            if (StringUtils.hasText(detectedMimeType) && !"application/octet-stream".equalsIgnoreCase(detectedMimeType)) {
                return detectedMimeType;
            }
            String normalizedFallback = normalizeMimeType(fallbackMimeType);
            return StringUtils.hasText(normalizedFallback) ? normalizedFallback : detectedMimeType;
        } catch (IOException e) {
            throw new ServiceException(DOCUMENT_MIME_FAILED);
        }
    }

    private static void validateSource(InputStreamSource source, String originalFileName, String detectedMimeType) {
        validate(originalFileName, detectedMimeType, readFileHeader(source));
    }

    private static byte[] readFileHeader(InputStreamSource source) {
        try (InputStream inputStream = source.getInputStream()) {
            return inputStream.readNBytes(MAGIC_HEADER_BYTES);
        } catch (IOException e) {
            throw new ServiceException("文件头读取失败: " + e.getMessage(), e, DOCUMENT_PARSE_FAILED);
        }
    }

    private static String stripExtension(String fileName, String fallbackBaseName) {
        if (!StringUtils.hasText(fileName)) {
            return fallbackBaseName;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }

    private static String requireFilename(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            throw new ClientException("文件名不能为空", DOCUMENT_TYPE_NOT_SUPPORTED);
        }
        return fileName.trim();
    }

    private static Map<String, Set<String>> createExtensionMimeMapping() {
        Map<String, Set<String>> mapping = new LinkedHashMap<>();
        mapping.put("pdf", Set.of("application/pdf"));
        mapping.put("doc", Set.of("application/msword"));
        mapping.put("docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        mapping.put("xls", Set.of("application/vnd.ms-excel"));
        mapping.put("xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        mapping.put("ppt", Set.of("application/vnd.ms-powerpoint"));
        mapping.put("pptx", Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        mapping.put("rtf", Set.of("application/rtf", "text/rtf"));
        mapping.put("txt", Set.of("text/plain"));
        mapping.put("csv", Set.of("text/csv"));
        mapping.put("md", Set.of("text/markdown"));
        mapping.put("html", Set.of("text/html"));
        mapping.put("htm", Set.of("text/html"));
        return Collections.unmodifiableMap(mapping);
    }

}
