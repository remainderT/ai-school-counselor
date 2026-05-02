package org.buaa.rag.controller;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.dao.entity.ChunkDO;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dto.req.ChunkPageReqDTO;
import org.buaa.rag.dto.req.DocumentPageReqDTO;
import org.buaa.rag.dto.req.DocumentUploadReqDTO;
import org.buaa.rag.dto.resp.DocumentDetailRespDTO;
import org.buaa.rag.dto.resp.DocumentPageRespDTO;
import org.buaa.rag.dto.resp.PageResponseDTO;
import org.buaa.rag.service.DocumentService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @PostMapping("/full-import")
    public Result<String> fullImport() {
        return Results.success(documentService.fullImport());
    }

    @GetMapping("/list")
    public Result<List<DocumentDO>> list(
            @RequestParam(required = false) Long knowledgeId,
            @RequestParam(required = false) String name) {
        return Results.success(documentService.list(knowledgeId, name));
    }

    @GetMapping("/page")
    public Result<PageResponseDTO<DocumentPageRespDTO>> page(DocumentPageReqDTO request) {
        return Results.success(documentService.pageList(request));
    }

    @GetMapping("/{id}/detail")
    public Result<DocumentDetailRespDTO> detail(@PathVariable Long id) {
        return Results.success(documentService.detail(id));
    }

    @GetMapping("/{id}/chunks")
    public Result<List<ChunkDO>> listChunks(@PathVariable Long id) {
        return Results.success(documentService.listChunks(id));
    }

    @GetMapping("/{id}/chunks/page")
    public Result<PageResponseDTO<ChunkDO>> pageChunks(@PathVariable Long id, ChunkPageReqDTO request) {
        return Results.success(documentService.pageChunks(id, request));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id) {
        DocumentDetailRespDTO detail = documentService.detail(id);
        InputStream inputStream = documentService.downloadStream(id);
        String filename = detail.getOriginalFileName() == null ? ("document-" + id) : detail.getOriginalFileName();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(filename, StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(new InputStreamResource(inputStream));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        documentService.delete(id);
        return Results.success();
    }
}
