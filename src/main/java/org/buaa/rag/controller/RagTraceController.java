package org.buaa.rag.controller;

import java.util.List;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.dto.resp.RagTraceNodeVO;
import org.buaa.rag.dto.resp.RagTraceRunVO;
import org.buaa.rag.service.RagTraceQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;

import lombok.RequiredArgsConstructor;

/**
 * RAG 全链路 Trace 查询接口
 *
 * <p>接口列表：
 * <ul>
 *   <li>GET /api/rag/traces/runs — 分页查询链路运行记录</li>
 *   <li>GET /api/rag/traces/runs/{traceId}/nodes — 查询链路节点列表</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/rag/traces")
@RequiredArgsConstructor
public class RagTraceController {

    private final RagTraceQueryService ragTraceQueryService;

    /**
     * 分页查询链路运行记录列表
     *
     * @param current        当前页（默认 1）
     * @param size           每页条数（默认 20，最大 100）
     * @param traceId        链路 ID 精确过滤
     * @param conversationId 会话 ID 过滤
     * @param taskId         任务 ID 过滤
     * @param status         状态过滤（RUNNING/SUCCESS/ERROR）
     */
    @GetMapping("/runs")
    public Result<IPage<RagTraceRunVO>> pageRuns(
            @RequestParam(defaultValue = "1")  int    current,
            @RequestParam(defaultValue = "20") int    size,
            @RequestParam(required = false)    String traceId,
            @RequestParam(required = false)    String conversationId,
            @RequestParam(required = false)    String taskId,
            @RequestParam(required = false)    String status) {
        return Results.success(
            ragTraceQueryService.pageRuns(current, size, traceId, conversationId, taskId, status)
        );
    }

    /**
     * 查询指定链路的所有节点（按 startTime 升序，用于前端瀑布图渲染）
     *
     * @param traceId 链路 ID
     */
    @GetMapping("/runs/{traceId}/nodes")
    public Result<List<RagTraceNodeVO>> listNodes(@PathVariable String traceId) {
        return Results.success(ragTraceQueryService.listNodes(traceId));
    }
}
