package org.buaa.rag.service;

import java.util.List;

import org.buaa.rag.dao.entity.IntentNodeDO;
import org.buaa.rag.dto.req.IntentNodeCreateReqDTO;
import org.buaa.rag.dto.req.IntentNodeUpdateReqDTO;
import org.buaa.rag.dto.resp.IntentNodeTreeRespDTO;

import com.baomidou.mybatisplus.extension.service.IService;

public interface IntentTreeService extends IService<IntentNodeDO> {

    /**
     * 查询意图树
     */
    List<IntentNodeTreeRespDTO> tree();

    /**
     * 创建意图节点
     */
    Long create(IntentNodeCreateReqDTO requestParam);

    /**
     * 更新意图节点
     */
    void update(Long id, IntentNodeUpdateReqDTO requestParam);

    /**
     * 删除意图节点
     */
    void delete(Long id);

    /**
     * 批量启用意图节点
     */
    void batchEnable(List<Long> ids);

    /**
     * 批量禁用意图节点
     */
    void batchDisable(List<Long> ids);

    /**
     * 批量删除意图节点
     */
    void batchDelete(List<Long> ids);
}
