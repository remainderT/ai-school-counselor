package org.buaa.rag.service;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.dao.entity.DocumentDO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文档服务接口
 */
public interface DocumentService {

    /**
     * 上传文档
     */
    Result<Map<String, Object>> upload(MultipartFile file,
                                       String visibility,
                                       String department,
                                       String docType,
                                       String policyYear,
                                       String tags);

    /**
     * 列出用户的文档
     */
    Result<List<DocumentDO>> listDocuments(String userId);

    /**
     * 删除文档
     */
    Result<Map<String, Object>> deleteDocument(String md5Hash, String userId);

    /**
     * 异步摄取文档
     */
    void ingestDocumentAsync(String documentMd5, String originalFileName);
}
