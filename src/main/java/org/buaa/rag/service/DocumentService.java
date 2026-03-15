package org.buaa.rag.service;

import java.util.List;

import org.buaa.rag.dao.entity.DocumentDO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档服务接口
 */
public interface DocumentService {

    /**
     * 上传文档
     */
    void upload(MultipartFile file, Long knowledgeId);

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
    void ingestDocument(String documentMd5, String originalFileName);

    /**
     * 标记文档摄取最终失败
     */
    void markIngestionFinalFailure(String documentMd5, String failureReason);
}
