package org.buaa.rag.dto.req;

import org.springframework.web.multipart.MultipartFile;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一文档上传请求
 * <p>
 * 支持文件上传和 URL 上传两种来源
 */
@Data
@NoArgsConstructor
public class DocumentUploadReqDTO {

    private MultipartFile file;

    private String url;

    private Long knowledgeId;
}
