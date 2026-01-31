package org.buaa.rag.controller;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.service.DocumentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/rag/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public Result<Map<String, Object>> handleFileUpload(@RequestParam("file") MultipartFile uploadedFile,
                                                        @RequestParam(defaultValue = "anonymous") String userId,
                                                        @RequestParam(defaultValue = "PRIVATE") String visibility,
                                                        @RequestParam(required = false) String department,
                                                        @RequestParam(required = false) String docType,
                                                        @RequestParam(required = false) String policyYear,
                                                        @RequestParam(required = false) String tags) {
        return documentService.upload(uploadedFile, userId, visibility, department, docType, policyYear, tags);
    }

    @GetMapping("/list")
    public Result<List<DocumentDO>> listDocuments(@RequestParam String userId) {
        return documentService.listDocuments(userId);
    }

    @DeleteMapping("/{md5Hash}")
    public Result<Map<String, Object>> deleteDocument(@PathVariable String md5Hash,
                                                      @RequestParam String userId) {
        return documentService.deleteDocument(md5Hash, userId);
    }
}
