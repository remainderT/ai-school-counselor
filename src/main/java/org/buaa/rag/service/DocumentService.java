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
     *
     * @param knowledgeId 知识库 ID（可选）
     * @param name        文档名称关键词（可选，模糊匹配）
     */
    List<DocumentDO> list(Long knowledgeId, String name);

    /**
     * 删除文档
     */
    void delete(String id);

    /**
     * 查询文档下的所有 chunk 列表
     *
     * @param documentId 文档 ID
     */
    List<ChunkDO> listChunks(Long documentId);

}
