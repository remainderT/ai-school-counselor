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
    void upload(MultipartFile file, String visibility);

    /**
     * 列出用户的文档
     */
    List<DocumentDO> list();

    /**
     * 删除文档
     */
    void delete(String id);

    /**
     * 处理文档摄取任务
     */
    void ingestDocumentTask(String documentMd5, String originalFileName);
}
