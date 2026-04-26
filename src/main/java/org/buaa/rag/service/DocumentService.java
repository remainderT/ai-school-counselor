package org.buaa.rag.service;

import java.util.List;

import org.buaa.rag.dao.entity.ChunkDO;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dto.req.DocumentUploadReqDTO;

/**
 * 文档服务接口
 */
public interface DocumentService {

    /**
     * 统一上传文档
     */
    void upload(DocumentUploadReqDTO request);

    /**
     * 列出用户的文档（支持按知识库过滤和名称搜索）
     */
    List<DocumentDO> list(Long knowledgeId, String name);

    /**
     * 删除文档
     */
    void delete(String id);

    /**
     * 查询文档下的所有 chunk 列表
     */
    List<ChunkDO> listChunks(Long documentId);

    /**
     * 全量导入文档
     */
    String fullImport();

}
