package org.buaa.rag.service.impl;

import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_ACCESS_CONTROL_ERROR;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_EXISTS;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_NAME_NULL;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_NOT_EXISTS;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_PARSE_FAILED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_SIZE_EXCEEDED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_UPLOAD_FAILED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_UPLOAD_NULL;
import static org.buaa.rag.common.enums.KnowledgeErrorCodeEnum.KNOWLEDGE_ACCESS_DENIED;
import static org.buaa.rag.common.enums.KnowledgeErrorCodeEnum.KNOWLEDGE_ID_REQUIRED;
import static org.buaa.rag.common.enums.KnowledgeErrorCodeEnum.KNOWLEDGE_NOT_EXISTS;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.STORAGE_SERVICE_ERROR;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;
import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.KnowledgeMapper;
import org.buaa.rag.module.ingestion.DocumentArtifactService;
import org.buaa.rag.module.ingestion.DocumentLifecycleService;
import org.buaa.rag.module.ingestion.DocumentIngestionTask;
import org.buaa.rag.tool.RemoteURLFetcher;
import org.buaa.rag.tool.RemoteURLFetcher.FetchedRemoteDocument;
import org.buaa.rag.mq.IngestionProducer;
import org.buaa.rag.properties.FIleParseProperties;
import org.buaa.rag.service.DocumentService;
import org.buaa.rag.module.parser.FileTypeValidate;
import org.buaa.rag.module.parser.FileTypeValidate.InspectedFile;
import org.buaa.rag.tool.RustfsStorage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档服务实现层
 * <p>
 * 上传阶段仅负责校验、存储、建档和入队；离线摄取由独立模块处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, DocumentDO> implements DocumentService {

    private static final String REMOTE_FILE_BASENAME = "remote-document";

    private final RustfsStorage rustfsStorage;
    private final KnowledgeMapper knowledgeMapper;
    private final FIleParseProperties fileParseProperties;
    private final IngestionProducer ingestionProducer;
    private final DocumentLifecycleService lifecycleService;
    private final DocumentArtifactService artifactService;
    private final RemoteURLFetcher remoteURLFetcher;


    private record UploadPayload(String originalFilename,
                                 String mimeType,
                                 long size,
                                 String md5,
                                 InputStreamSource source) {
    }

    @Override
    public void upload(MultipartFile uploadedFile, String url, Long knowledgeId) {
        KnowledgeDO knowledge = verifyUploadKnowledge(knowledgeId);
        UploadPayload payload = uploadedFile != null ? buildMultipartUploadPayload(uploadedFile) : buildUrlUploadPayload(url);
        persistUploadPayload(payload, knowledge.getId());
    }

    @Override
    public List<DocumentDO> list() {
        return baseMapper.selectList(
            Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getUserId, UserContext.getUserId())
                .eq(DocumentDO::getDelFlag, 0)
        );
    }

    @Override
    public void delete(String id) {
        DocumentDO document = baseMapper.selectById(id);
        if (document == null || document.getDelFlag() != 0) {
            throw new ClientException(DOCUMENT_NOT_EXISTS);
        }
        if (!document.getUserId().equals(UserContext.getUserId())) {
            throw new ClientException(DOCUMENT_ACCESS_CONTROL_ERROR);
        }

        deleteStoredDocument(document.getMd5Hash(), document.getOriginalFileName());
        artifactService.cleanup(document);
        markDeleted(document.getId());
    }

    private void markDeleted(Long documentId) {
        baseMapper.update(
            null,
            Wrappers.lambdaUpdate(DocumentDO.class)
                .eq(DocumentDO::getId, documentId)
                .eq(DocumentDO::getDelFlag, 0)
                .set(DocumentDO::getDelFlag, 1)
        );
    }

    private UploadPayload buildMultipartUploadPayload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ClientException(DOCUMENT_UPLOAD_NULL);
        }
        if (file.getSize() > fileParseProperties.getMaxUploadBytes()) {
            throw new ClientException(DOCUMENT_SIZE_EXCEEDED);
        }
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            throw new ClientException(DOCUMENT_NAME_NULL);
        }
        InspectedFile inspectedFile = FileTypeValidate.inspectLocal(file, originalFilename);
        String md5 = calculateMd5(file);
        return new UploadPayload(inspectedFile.fileName(), inspectedFile.mimeType(), file.getSize(), md5, file);
    }

    private UploadPayload buildUrlUploadPayload(String sourceUrl) {
        FetchedRemoteDocument remoteDocument = remoteURLFetcher.fetch(sourceUrl, fileParseProperties.getMaxUploadBytes());
        byte[] body = remoteDocument.body();
        if (body == null || body.length == 0) {
            throw new ClientException(DOCUMENT_UPLOAD_NULL);
        }
        ByteArrayResource source = new ByteArrayResource(body);
        InspectedFile inspectedFile = FileTypeValidate.inspectRemote(
            source,
            remoteDocument.fileName(),
            remoteDocument.contentType(),
            REMOTE_FILE_BASENAME);
        String md5 = DigestUtils.md5Hex(body);
        return new UploadPayload(inspectedFile.fileName(), inspectedFile.mimeType(), body.length, md5, source);
    }

    private void persistUploadPayload(UploadPayload payload, Long knowledgeId) {
        ensureDocumentNotExists(payload.md5());
        DocumentDO document = null;
        try {
            storeDocument(payload);
            document = createPendingDocument(payload.md5(), payload.originalFilename(), payload.size(), knowledgeId);
            enqueueIngestionTask(document.getId());
        } catch (Exception e) {
            if (document == null) {
                cleanupStoredDocumentQuietly(payload.md5(), payload.originalFilename());
            }
            markUploadFailure(document, e);
            throw new ServiceException("文件上传失败: " + e.getMessage(), e, DOCUMENT_UPLOAD_FAILED);
        }
    }

    private KnowledgeDO verifyUploadKnowledge(Long knowledgeId) {
        if (knowledgeId == null) {
            throw new ClientException(KNOWLEDGE_ID_REQUIRED);
        }
        KnowledgeDO knowledge = knowledgeMapper.selectOne(
            Wrappers.lambdaQuery(KnowledgeDO.class)
                .eq(KnowledgeDO::getId, knowledgeId)
                .eq(KnowledgeDO::getDelFlag, 0)
        );
        if (knowledge == null) {
            throw new ClientException(KNOWLEDGE_NOT_EXISTS);
        }
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null || !currentUserId.equals(knowledge.getUserId())) {
            throw new ClientException(KNOWLEDGE_ACCESS_DENIED);
        }
        return knowledge;
    }

    private String calculateMd5(InputStreamSource source) {
        try (InputStream inputStream = source.getInputStream()) {
            return DigestUtils.md5Hex(inputStream);
        } catch (IOException e) {
            throw new ServiceException("文档解析失败: " + e.getMessage(), e, DOCUMENT_PARSE_FAILED);
        }
    }

    private void ensureDocumentNotExists(String md5) {
        DocumentDO existing = lifecycleService.findActiveByMd5AndUserId(md5, UserContext.getUserId());
        if (existing != null) {
            throw new ClientException(DOCUMENT_EXISTS);
        }
    }

    private void storeDocument(UploadPayload payload) {
        try {
            rustfsStorage.upload(
                rustfsStorage.buildPrimaryPath(payload.md5(), payload.originalFilename()),
                payload.source().getInputStream(),
                payload.size(),
                StringUtils.hasText(payload.mimeType()) ? payload.mimeType() : null
            );
        } catch (IOException e) {
            throw new ServiceException("文件读取失败: " + e.getMessage(), e, DOCUMENT_UPLOAD_FAILED);
        } catch (Exception e) {
            throw new ServiceException("对象存储上传失败: " + e.getMessage(), e, STORAGE_SERVICE_ERROR);
        }
    }

    private DocumentDO createPendingDocument(String md5, String filename, long size, Long knowledgeId) {
        return lifecycleService.createPendingDocument(
            md5,
            filename,
            size,
            knowledgeId,
            UserContext.getUserId()
        );
    }

    private void enqueueIngestionTask(Long documentId) {
        ingestionProducer.enqueue(DocumentIngestionTask.initial(documentId));
    }

    private void deleteStoredDocument(String md5, String filename) {
        String primaryPath = rustfsStorage.buildPrimaryPath(md5, filename);
        try {
            rustfsStorage.delete(primaryPath);
        } catch (Exception e) {
            throw new ServiceException("对象存储删除失败: " + e.getMessage(), e, STORAGE_SERVICE_ERROR);
        }
    }

    private void cleanupStoredDocumentQuietly(String md5, String filename) {
        if (!StringUtils.hasText(md5) || !StringUtils.hasText(filename)) {
            return;
        }
        try {
            deleteStoredDocument(md5, filename);
        } catch (Exception cleanupError) {
            log.warn("上传失败后清理对象存储文件失败: md5={}", md5, cleanupError);
        }
    }

    private void markUploadFailure(DocumentDO document, Exception error) {
        if (document == null || document.getId() == null) {
            log.error("文件上传失败，未能建立文档记录", error);
            return;
        }
        log.error("文件上传失败: documentId={}", document.getId(), error);
        lifecycleService.markFailed(document.getId(), error);
    }
}
