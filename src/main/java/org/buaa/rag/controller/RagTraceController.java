package org.buaa.rag.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * 辅导员系统全链路追踪查询接口
 *
 * <p>接口列表：
 * <ul>
 *   <li>GET /api/rag/traces/runs — 分页查询链路运行记录</li>
 *   <li>GET /api/rag/traces/runs/{traceId}/nodes — 查询链路详情（run + nodes）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/rag/traces")
@RequiredArgsConstructor
public class RagTraceController {

    private final RagTraceQueryService ragTraceQueryService;

    /**
     * 分页查询链路运行记录列表
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
     * 查询指定链路的详情，包含 run 元信息和全部 nodes（用于前端瀑布图渲染）
     *
     * @param traceId 链路 ID
     */
    @GetMapping("/runs/{traceId}/nodes")
    public Result<Map<String, Object>> traceDetail(@PathVariable String traceId) {
        RagTraceRunVO run = ragTraceQueryService.getRunByTraceId(traceId);
        List<RagTraceNodeVO> nodes = ragTraceQueryService.listNodes(traceId);
        Map<String, Object> detail = new HashMap<>();
        detail.put("run", run);
        detail.put("nodes", nodes);
        return Results.success(detail);
    }
}
