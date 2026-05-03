package org.buaa.rag.service;

import java.util.List;

import org.buaa.rag.dto.resp.RagTraceNodeVO;
import org.buaa.rag.dto.resp.RagTraceRunVO;

import com.baomidou.mybatisplus.core.metadata.IPage;

/**
 * RAG 链路 Trace 查询服务（管理台侧）
 */
public interface RagTraceQueryService {

    /**
     * 分页查询链路运行记录列表
     */
    IPage<RagTraceRunVO> pageRuns(int current, int size,
                                   String traceId, String conversationId,
                                   String taskId, String status);

    /**
     * 根据 traceId 查询单条运行记录
     */
    RagTraceRunVO getRunByTraceId(String traceId);

    /**
     * 查询链路节点列表（按 startTime 升序，支持瀑布图渲染）
     *
     * @param traceId 链路 ID
     */
    List<RagTraceNodeVO> listNodes(String traceId);
}
