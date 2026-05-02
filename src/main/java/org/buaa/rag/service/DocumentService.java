package org.buaa.rag.service;

import java.io.InputStream;
import java.util.List;

import org.buaa.rag.dao.entity.ChunkDO;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dto.req.ChunkPageReqDTO;
import org.buaa.rag.dto.req.DocumentPageReqDTO;
import org.buaa.rag.dto.req.DocumentUploadReqDTO;
import org.buaa.rag.dto.resp.DocumentDetailRespDTO;
import org.buaa.rag.dto.resp.DocumentPageRespDTO;
import org.buaa.rag.dto.resp.PageResponseDTO;

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
     * 分页查询文档列表
     */
    PageResponseDTO<DocumentPageRespDTO> pageList(DocumentPageReqDTO request);

    /**
     * 删除文档
     */
    void delete(String id);

    /**
     * 查询文档下的所有 chunk 列表
     */
    List<ChunkDO> listChunks(Long documentId);

    /**
     * 分页查询文档下的 chunk 列表
     */
    PageResponseDTO<ChunkDO> pageChunks(Long documentId, ChunkPageReqDTO request);

    /**
     * 查询文档详情（包含 chunks）
     */
    DocumentDetailRespDTO detail(Long documentId);

    /**
     * 下载原始文档
     */
    byte[] download(Long documentId);

    /**
     * 流式下载原始文档（用于大文件）
     */
    InputStream downloadStream(Long documentId);

    /**
     * 全量导入文档
     */
    String fullImport();

}
