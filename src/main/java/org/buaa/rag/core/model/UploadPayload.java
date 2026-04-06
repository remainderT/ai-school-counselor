package org.buaa.rag.core.model;

import org.springframework.core.io.InputStreamSource;

/**
 * 文档上传载荷，封装上传/刷新文档的元信息与数据源。
 */
public record UploadPayload(String originalFilename,
                             String mimeType,
                             long size,
                             String md5,
                             InputStreamSource source) {
}
