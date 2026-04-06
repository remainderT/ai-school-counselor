package org.buaa.rag.service;

import java.util.List;

import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dto.req.KnowledgeCreateReqDTO;
import org.buaa.rag.dto.req.KnowledgeUpdateReqDTO;
import org.buaa.rag.dto.resp.KnowledgeListRespDTO;

/**
 * 知识库服务
 */
public interface KnowledgeService {

    /**
     * 创建知识库
     */
    Long create(KnowledgeCreateReqDTO requestParam);

    /**
     * 获取当前用户知识库列表（含文档数量）
     */
    List<KnowledgeListRespDTO> listMine();

    /**
     * 查询知识库详情
     */
    KnowledgeDO detail(Long id);

    /**
     * 更新知识库
     */
    void update(Long id, KnowledgeUpdateReqDTO requestParam);

    /**
     * 删除知识库
     */
    void delete(Long id);
}
