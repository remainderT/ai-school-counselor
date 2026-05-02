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
     *
     * @param current       当前页（1-based）
     * @param size          每页条数
     * @param traceId       链路 ID 精确过滤（可为空）
     * @param conversationId 会话 ID 过滤（可为空）
     * @param taskId        任务 ID 过滤（可为空）
     * @param status        状态过滤：RUNNING / SUCCESS / ERROR（可为空）
     */
    IPage<RagTraceRunVO> pageRuns(int current, int size,
                                   String traceId, String conversationId,
                                   String taskId, String status);

    /**
     * 查询链路节点列表（按 startTime 升序，支持瀑布图渲染）
     *
     * @param traceId 链路 ID
     */
    List<RagTraceNodeVO> listNodes(String traceId);
}
