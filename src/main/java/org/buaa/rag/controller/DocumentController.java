package org.buaa.rag.controller;

import java.util.List;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.dao.entity.ChunkDO;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dto.req.DocumentUploadReqDTO;
import org.buaa.rag.service.DocumentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/rag/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public Result<Void> upload(@ModelAttribute DocumentUploadReqDTO request) {
        documentService.upload(request);
        return Results.success();
    }

    @GetMapping("/list")
    public Result<List<DocumentDO>> list(
            @RequestParam(required = false) Long knowledgeId,
            @RequestParam(required = false) String name) {
        return Results.success(documentService.list(knowledgeId, name));
    }

    @GetMapping("/{id}/chunks")
    public Result<List<ChunkDO>> listChunks(@PathVariable Long id) {
        return Results.success(documentService.listChunks(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        documentService.delete(id);
        return Results.success();
    }
}
