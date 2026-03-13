package org.buaa.rag.controller;

import java.util.List;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dto.req.KnowledgeCreateReqDTO;
import org.buaa.rag.dto.req.KnowledgeUpdateReqDTO;
import org.buaa.rag.service.KnowledgeService;
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
@RequestMapping("/api/rag/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @PostMapping
    public Result<Long> create(@RequestBody KnowledgeCreateReqDTO requestParam) {
        return Results.success(knowledgeService.create(requestParam));
    }

    @GetMapping("/list")
    public Result<List<KnowledgeDO>> list() {
        return Results.success(knowledgeService.listMine());
    }

    @GetMapping("/{id}")
    public Result<KnowledgeDO> detail(@PathVariable Long id) {
        return Results.success(knowledgeService.detail(id));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody KnowledgeUpdateReqDTO requestParam) {
        knowledgeService.update(id, requestParam);
        return Results.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeService.delete(id);
        return Results.success();
    }
}
