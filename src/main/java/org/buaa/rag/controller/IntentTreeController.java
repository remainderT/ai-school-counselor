package org.buaa.rag.controller;

import java.util.List;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.dto.req.IntentNodeBatchReqDTO;
import org.buaa.rag.dto.req.IntentNodeCreateReqDTO;
import org.buaa.rag.dto.req.IntentNodeUpdateReqDTO;
import org.buaa.rag.dto.resp.IntentNodeTreeRespDTO;
import org.buaa.rag.service.IntentTreeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/rag/intent-tree")
@RequiredArgsConstructor
public class IntentTreeController {

    private final IntentTreeService intentTreeService;

    @GetMapping("/trees")
    public Result<List<IntentNodeTreeRespDTO>> tree() {
        return Results.success(intentTreeService.tree());
    }

    @PostMapping
    public Result<Long> create(@RequestBody IntentNodeCreateReqDTO requestParam) {
        return Results.success(intentTreeService.create(requestParam));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody IntentNodeUpdateReqDTO requestParam) {
        intentTreeService.update(id, requestParam);
        return Results.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        intentTreeService.delete(id);
        return Results.success();
    }

    @PostMapping("/batch/enable")
    public Result<Void> batchEnable(@RequestBody IntentNodeBatchReqDTO requestParam) {
        intentTreeService.batchEnable(requestParam == null ? null : requestParam.getIds());
        return Results.success();
    }

    @PostMapping("/batch/disable")
    public Result<Void> batchDisable(@RequestBody IntentNodeBatchReqDTO requestParam) {
        intentTreeService.batchDisable(requestParam == null ? null : requestParam.getIds());
        return Results.success();
    }

    @PostMapping("/batch/delete")
    public Result<Void> batchDelete(@RequestBody IntentNodeBatchReqDTO requestParam) {
        intentTreeService.batchDelete(requestParam == null ? null : requestParam.getIds());
        return Results.success();
    }

}
