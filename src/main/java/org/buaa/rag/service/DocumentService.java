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
    void upload(MultipartFile file,
                                       String visibility,
                                       String department,
                                       String docType,
                                       String policyYear,
                                       String tags);

    /**
     * 列出用户的文档
     */
    List<DocumentDO> list();

    /**
     * 删除文档
     */
    void delete(String id);

    /**
     * 异步摄取文档
     */
    void ingestDocumentAsync(String documentMd5, String originalFileName);
}
