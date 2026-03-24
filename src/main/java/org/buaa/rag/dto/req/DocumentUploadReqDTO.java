package org.buaa.rag.dto.req;

import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一文档上传请求
 * <p>
 * 支持文件上传和 URL 上传两种来源，service 层统一走一个入口。
 */
@Data
@NoArgsConstructor
public class DocumentUploadReqDTO {

    private MultipartFile file;

    private String url;

    private Long knowledgeId;

    public static DocumentUploadReqDTO forFile(MultipartFile file, Long knowledgeId) {
        DocumentUploadReqDTO request = new DocumentUploadReqDTO();
        request.setFile(file);
        request.setKnowledgeId(knowledgeId);
        return request;
    }

    public static DocumentUploadReqDTO forUrl(String url, Long knowledgeId) {
        DocumentUploadReqDTO request = new DocumentUploadReqDTO();
        request.setUrl(url);
        request.setKnowledgeId(knowledgeId);
        return request;
    }

    public boolean hasFile() {
        return file != null && !file.isEmpty();
    }

    public boolean hasUrl() {
        return StringUtils.hasText(url);
    }
}
