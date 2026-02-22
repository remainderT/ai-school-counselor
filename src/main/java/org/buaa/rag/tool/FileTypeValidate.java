package org.buaa.rag.tool;

import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_TYPE_NOT_SUPPORTED;

import java.util.Locale;
import java.util.Set;

import org.buaa.rag.common.convention.exception.ClientException;
import org.springframework.util.StringUtils;

public class FileTypeValidate {

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

    public static void validate(String originalFileName, String detectedMimeType, byte[] fileHeader) {
        String extension = extractExtension(originalFileName);
        if (!ALLOWED_FILE_TYPES.contains(extension)) {
            throw new ClientException(DOCUMENT_TYPE_NOT_SUPPORTED);
        }

        if (!isMimeAllowed(detectedMimeType, extension)) {
            throw new ClientException("文件类型校验失败，请确认文件格式", DOCUMENT_TYPE_NOT_SUPPORTED);
        }
        if (!isMagicNumberAllowed(extension, fileHeader)) {
            throw new ClientException("文件头校验失败，请确认文件内容与扩展名一致", DOCUMENT_TYPE_NOT_SUPPORTED);
        }
    }

    private static String extractExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isMimeAllowed(String mimeType, String extension) {
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
}
