package org.buaa.rag.controller;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.dao.entity.ChunkDO;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dto.req.ChunkPageReqDTO;
import org.buaa.rag.dto.req.ChunkUpdateReqDTO;
import org.buaa.rag.dto.req.DocumentPageReqDTO;
import org.buaa.rag.dto.req.DocumentUploadReqDTO;
import org.buaa.rag.dto.resp.DocumentDetailRespDTO;
import org.buaa.rag.dto.resp.DocumentPageRespDTO;
import org.buaa.rag.dto.resp.PageResponseDTO;
import org.buaa.rag.service.DocumentService;
import jakarta.validation.Valid;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    public Result<Void> upload(@Valid @ModelAttribute DocumentUploadReqDTO request) {
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
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id) throws Exception {
        DocumentDetailRespDTO detail = documentService.detail(id);
        String filename = detail.getOriginalFileName() == null ? ("document-" + id) : detail.getOriginalFileName();

        // 将 S3/RustFS 的网络流完整读入内存，避免因网络流提前关闭导致客户端连接中断
        byte[] content;
        try (InputStream inputStream = documentService.downloadStream(id)) {
            content = inputStream.readAllBytes();
        }

        // 手动构建 Content-Disposition，避免 Spring ContentDisposition 使用 RFC 2047 Q-encoding
        // 浏览器不支持 RFC 2047，会导致中文文件名显示为 =_UTF-8_Q_=E5=... 乱码
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        String dispositionValue = "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename;

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, dispositionValue)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(content.length)
            .body(new InputStreamResource(new ByteArrayInputStream(content)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return Results.success();
    }

    @PutMapping("/{documentId}/chunks/{chunkId}")
    public Result<Void> updateChunk(@PathVariable Long documentId,
                                    @PathVariable Long chunkId,
                                    @Valid @RequestBody ChunkUpdateReqDTO request) {
        documentService.updateChunk(documentId, chunkId, request);
        return Results.success();
    }

    @DeleteMapping("/{documentId}/chunks/{chunkId}")
    public Result<Void> deleteChunk(@PathVariable Long documentId,
                                    @PathVariable Long chunkId) {
        documentService.deleteChunk(documentId, chunkId);
        return Results.success();
    }

    @PutMapping("/{documentId}/chunks/{chunkId}/toggle")
    public Result<Void> toggleChunkEnabled(@PathVariable Long documentId,
                                           @PathVariable Long chunkId,
                                           @RequestParam boolean enabled) {
        documentService.toggleChunkEnabled(documentId, chunkId, enabled);
        return Results.success();
    }
}
