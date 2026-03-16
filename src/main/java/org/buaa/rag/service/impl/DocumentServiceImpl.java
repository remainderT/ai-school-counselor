package org.buaa.rag.service.impl;

import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_ACCESS_CONTROL_ERROR;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_EXISTS;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_MIME_FAILED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_NOT_EXISTS;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_PARSE_FAILED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_SIZE_EXCEEDED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_TYPE_NOT_SUPPORTED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_UPLOAD_FAILED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_UPLOAD_NULL;
import static org.buaa.rag.common.enums.KnowledgeErrorCodeEnum.KNOWLEDGE_ACCESS_DENIED;
import static org.buaa.rag.common.enums.KnowledgeErrorCodeEnum.KNOWLEDGE_ID_REQUIRED;
import static org.buaa.rag.common.enums.KnowledgeErrorCodeEnum.KNOWLEDGE_NOT_EXISTS;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.STORAGE_SERVICE_ERROR;
import static org.buaa.rag.common.enums.UploadStatusEnum.FAILED_FINAL;
import static org.buaa.rag.common.enums.UploadStatusEnum.PENDING;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.tika.Tika;
import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.KnowledgeMapper;
import org.buaa.rag.module.ingestion.DocumentIngestionFailureResolver;
import org.buaa.rag.module.ingestion.DocumentIngestionTask;
import org.buaa.rag.module.ingestion.DocumentIngestionWorkflow;
import org.buaa.rag.mq.IngestionProducer;
import org.buaa.rag.properties.FIleParseProperties;
import org.buaa.rag.service.DocumentService;
import org.buaa.rag.tool.FileTypeValidate;
import org.buaa.rag.tool.RustfsStorage;
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

    private static final int MAGIC_HEADER_BYTES = 16;

    private final RustfsStorage rustfsStorage;
    private final KnowledgeMapper knowledgeMapper;
    private final FIleParseProperties fileParseProperties;
    private final IngestionProducer ingestionProducer;
    private final DocumentIngestionWorkflow ingestionWorkflow;
    private final DocumentIngestionFailureResolver failureResolver;

    private final Tika tika = new Tika();

    @Override
    public void upload(MultipartFile file, Long knowledgeId) {
        if (file == null || file.isEmpty()) {
            throw new ClientException(DOCUMENT_UPLOAD_NULL);
        }
        if (file.getSize() > fileParseProperties.getMaxUploadBytes()) {
            throw new ClientException(DOCUMENT_SIZE_EXCEEDED);
        }

        KnowledgeDO knowledge = verifyUploadKnowledge(knowledgeId);
        String originalFilename = requireOriginalFilename(file);
        byte[] fileHeader = readFileHeader(file);
        String mimeType = detectMimeType(file, originalFilename);
        FileTypeValidate.validate(originalFilename, mimeType, fileHeader);

        String md5 = calculateMd5(file);
        ensureDocumentNotExists(md5);

        try {
            storeDocument(file, md5, originalFilename, mimeType);
            createPendingDocument(md5, originalFilename, file.getSize(), knowledge.getId());
            enqueueIngestionTask(md5, originalFilename);
        } catch (Exception e) {
            markFinalFailure(md5, "文件上传失败", e);
            throw new ServiceException("文件上传失败: " + e.getMessage(), e, DOCUMENT_UPLOAD_FAILED);
        }
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
        ingestionWorkflow.cleanupArtifacts(document.getMd5Hash());
        baseMapper.deleteById(id);
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

    private String requireOriginalFilename(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            throw new ClientException("文件名不能为空", DOCUMENT_TYPE_NOT_SUPPORTED);
        }
        return originalFilename;
    }

    private String detectMimeType(MultipartFile file, String originalFilename) {
        try (InputStream inputStream = file.getInputStream()) {
            return tika.detect(inputStream, originalFilename);
        } catch (IOException e) {
            log.warn("MIME 检测失败: {}", e.getMessage());
            throw new ServiceException(DOCUMENT_MIME_FAILED);
        }
    }

    private String calculateMd5(MultipartFile file) {
        try {
            return DigestUtils.md5Hex(file.getInputStream());
        } catch (IOException e) {
            throw new ServiceException("文档解析失败: " + e.getMessage(), e, DOCUMENT_PARSE_FAILED);
        }
    }

    private void ensureDocumentNotExists(String md5) {
        DocumentDO existing = baseMapper.selectOne(
            Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getMd5Hash, md5)
                .eq(DocumentDO::getUserId, UserContext.getUserId())
        );
        if (existing != null) {
            throw new ClientException(DOCUMENT_EXISTS);
        }
    }

    private void storeDocument(MultipartFile file, String md5, String originalFilename, String mimeType) {
        try {
            rustfsStorage.upload(
                rustfsStorage.buildPrimaryPath(md5, originalFilename),
                file.getInputStream(),
                file.getSize(),
                StringUtils.hasText(mimeType) ? mimeType : file.getContentType()
            );
        } catch (IOException e) {
            throw new ServiceException("文件读取失败: " + e.getMessage(), e, DOCUMENT_UPLOAD_FAILED);
        } catch (Exception e) {
            throw new ServiceException("对象存储上传失败: " + e.getMessage(), e, STORAGE_SERVICE_ERROR);
        }
    }

    private void createPendingDocument(String md5, String filename, long size, Long knowledgeId) {
        DocumentDO record = new DocumentDO();
        record.setMd5Hash(md5);
        record.setOriginalFileName(filename);
        record.setFileSizeBytes(size);
        record.setProcessingStatus(PENDING.getCode());
        record.setUserId(UserContext.getUserId());
        record.setKnowledgeId(knowledgeId);
        record.setFailureReason(null);
        baseMapper.insert(record);
    }

    private void enqueueIngestionTask(String documentMd5, String originalFileName) {
        ingestionProducer.enqueue(DocumentIngestionTask.initial(documentMd5, originalFileName));
    }

    private void deleteStoredDocument(String md5, String filename) {
        String primaryPath = rustfsStorage.buildPrimaryPath(md5, filename);
        String legacyPath = rustfsStorage.buildLegacyPath(md5, filename);
        try {
            rustfsStorage.delete(primaryPath);
            if (StringUtils.hasText(legacyPath) && !primaryPath.equals(legacyPath)) {
                rustfsStorage.delete(legacyPath);
            }
        } catch (Exception e) {
            throw new ServiceException("对象存储删除失败: " + e.getMessage(), e, STORAGE_SERVICE_ERROR);
        }
    }

    private void markFinalFailure(String md5, String message, Exception error) {
        if (!StringUtils.hasText(md5)) {
            log.error(message, error);
            return;
        }
        log.error("{}: {}", message, md5, error);
        updateStatus(md5, FAILED_FINAL.getCode(), LocalDateTime.now(), failureResolver.summarizeFailureReason(error));
    }

    private boolean updateStatus(String md5, Integer status, LocalDateTime processedAt, String failureReason) {
        DocumentDO document = findByMd5(md5);
        if (document == null) {
            return false;
        }
        document.setProcessingStatus(status);
        document.setProcessedAt(processedAt);
        document.setFailureReason(failureResolver.normalizeFailureReason(failureReason));
        return baseMapper.updateById(document) > 0;
    }

    private DocumentDO findByMd5(String md5) {
        if (!StringUtils.hasText(md5)) {
            return null;
        }
        return baseMapper.selectOne(
            Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getMd5Hash, md5)
                .eq(DocumentDO::getDelFlag, 0)
                .last("limit 1")
        );
    }

    private byte[] readFileHeader(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return inputStream.readNBytes(MAGIC_HEADER_BYTES);
        } catch (IOException e) {
            throw new ServiceException("文件头读取失败: " + e.getMessage(), e, DOCUMENT_PARSE_FAILED);
        }
    }
}
