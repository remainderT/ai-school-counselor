package org.buaa.rag.service;

import java.util.List;

import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dto.req.DocumentUploadReqDTO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档服务接口
 */
public interface DocumentService {

    /**
     * 统一上传文档
     */
    void upload(MultipartFile uploadedFile, String url, Long knowledgeId);

    /**
     * 列出用户的文档
     */
    List<DocumentDO> list();

    /**
     * 删除文档
     */
    void delete(String id);

}
