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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.tika.Tika;
import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.KnowledgeMapper;
import org.buaa.rag.dto.req.DocumentUrlUploadReqDTO;
import org.buaa.rag.module.ingestion.DocumentIngestionFailureResolver;
import org.buaa.rag.module.ingestion.DocumentIngestionTask;
import org.buaa.rag.module.ingestion.DocumentIngestionWorkflow;
import org.buaa.rag.module.ingestion.RemoteDocumentFetcher;
import org.buaa.rag.module.ingestion.RemoteDocumentFetcher.FetchedRemoteDocument;
import org.buaa.rag.mq.IngestionProducer;
import org.buaa.rag.properties.FIleParseProperties;
import org.buaa.rag.service.DocumentService;
import org.buaa.rag.tool.FileTypeValidate;
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

    private static final int MAGIC_HEADER_BYTES = 16;
    private static final String REMOTE_FILE_BASENAME = "remote-document";
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "pdf", "doc", "docx", "txt", "md", "html", "htm",
        "xls", "xlsx", "ppt", "pptx", "rtf", "csv"
    );
    private static final Map<String, String> MIME_EXTENSION_MAPPING = Map.ofEntries(
        Map.entry("application/pdf", "pdf"),
        Map.entry("application/msword", "doc"),
        Map.entry("application/rtf", "rtf"),
        Map.entry("text/rtf", "rtf"),
        Map.entry("application/vnd.ms-excel", "xls"),
        Map.entry("application/vnd.ms-powerpoint", "ppt"),
        Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
        Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
        Map.entry("text/plain", "txt"),
        Map.entry("text/csv", "csv"),
        Map.entry("text/markdown", "md"),
        Map.entry("text/html", "html")
    );

    private final RustfsStorage rustfsStorage;
    private final KnowledgeMapper knowledgeMapper;
    private final FIleParseProperties fileParseProperties;
    private final IngestionProducer ingestionProducer;
    private final DocumentIngestionWorkflow ingestionWorkflow;
    private final DocumentIngestionFailureResolver failureResolver;
    private final RemoteDocumentFetcher remoteDocumentFetcher;

    private final Tika tika = new Tika();

    private record UploadPayload(String originalFilename,
                                 String mimeType,
                                 long size,
                                 String md5,
                                 InputStreamSource source) {
    }

    @Override
    public void upload(MultipartFile file, Long knowledgeId) {
        KnowledgeDO knowledge = verifyUploadKnowledge(knowledgeId);
        UploadPayload payload = buildMultipartUploadPayload(file);
        persistUploadPayload(payload, knowledge.getId());
    }

    @Override
    public void uploadByUrl(DocumentUrlUploadReqDTO requestParam) {
        if (requestParam == null) {
            throw new ClientException(DOCUMENT_UPLOAD_NULL);
        }
        KnowledgeDO knowledge = verifyUploadKnowledge(requestParam.getKnowledgeId());
        UploadPayload payload = buildUrlUploadPayload(requestParam.getUrl());
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
        ingestionWorkflow.cleanupArtifacts(document.getMd5Hash());
        baseMapper.deleteById(id);
    }

    private UploadPayload buildMultipartUploadPayload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ClientException(DOCUMENT_UPLOAD_NULL);
        }
        if (file.getSize() > fileParseProperties.getMaxUploadBytes()) {
            throw new ClientException(DOCUMENT_SIZE_EXCEEDED);
        }
        String originalFilename = requireOriginalFilename(file.getOriginalFilename());
        String mimeType = detectMimeType(file, originalFilename, null);
        validateUploadPayload(originalFilename, mimeType, file);
        String md5 = calculateMd5(file);
        return new UploadPayload(originalFilename, mimeType, file.getSize(), md5, file);
    }

    private UploadPayload buildUrlUploadPayload(String sourceUrl) {
        FetchedRemoteDocument remoteDocument = remoteDocumentFetcher.fetch(sourceUrl, fileParseProperties.getMaxUploadBytes());
        byte[] body = remoteDocument.body();
        if (body == null || body.length == 0) {
            throw new ClientException(DOCUMENT_UPLOAD_NULL);
        }
        ByteArrayResource source = new ByteArrayResource(body);
        String suggestedFileName = normalizeRemoteFileName(remoteDocument.fileName());
        String mimeType = detectMimeType(source, defaultMimeDetectionName(suggestedFileName), remoteDocument.contentType());
        String originalFilename = resolveRemoteFilename(suggestedFileName, mimeType);
        validateUploadPayload(originalFilename, mimeType, source);
        String md5 = DigestUtils.md5Hex(body);
        return new UploadPayload(originalFilename, mimeType, body.length, md5, source);
    }

    private void persistUploadPayload(UploadPayload payload, Long knowledgeId) {
        ensureDocumentNotExists(payload.md5());
        try {
            storeDocument(payload);
            createPendingDocument(payload.md5(), payload.originalFilename(), payload.size(), knowledgeId);
            enqueueIngestionTask(payload.md5(), payload.originalFilename());
        } catch (Exception e) {
            markFinalFailure(payload.md5(), "文件上传失败", e);
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

    private String requireOriginalFilename(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            throw new ClientException("文件名不能为空", DOCUMENT_TYPE_NOT_SUPPORTED);
        }
        return originalFilename.trim();
    }

    private String detectMimeType(InputStreamSource source, String originalFilename, String fallbackMimeType) {
        try (InputStream inputStream = source.getInputStream()) {
            String detectedMimeType = normalizeMimeType(tika.detect(inputStream, originalFilename));
            if (StringUtils.hasText(detectedMimeType) && !"application/octet-stream".equalsIgnoreCase(detectedMimeType)) {
                return detectedMimeType;
            }
            String normalizedFallback = normalizeMimeType(fallbackMimeType);
            return StringUtils.hasText(normalizedFallback) ? normalizedFallback : detectedMimeType;
        } catch (IOException e) {
            log.warn("MIME 检测失败: {}", e.getMessage());
            throw new ServiceException(DOCUMENT_MIME_FAILED);
        }
    }

    private void validateUploadPayload(String originalFilename, String mimeType, InputStreamSource source) {
        FileTypeValidate.validate(originalFilename, mimeType, readFileHeader(source));
    }

    private String calculateMd5(InputStreamSource source) {
        try (InputStream inputStream = source.getInputStream()) {
            return DigestUtils.md5Hex(inputStream);
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

    private byte[] readFileHeader(InputStreamSource source) {
        try (InputStream inputStream = source.getInputStream()) {
            return inputStream.readNBytes(MAGIC_HEADER_BYTES);
        } catch (IOException e) {
            throw new ServiceException("文件头读取失败: " + e.getMessage(), e, DOCUMENT_PARSE_FAILED);
        }
    }

    private String normalizeRemoteFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String normalized = fileName.trim();
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private String defaultMimeDetectionName(String suggestedFileName) {
        return StringUtils.hasText(suggestedFileName) ? suggestedFileName : REMOTE_FILE_BASENAME;
    }

    private String resolveRemoteFilename(String suggestedFileName, String mimeType) {
        String normalizedFileName = normalizeRemoteFileName(suggestedFileName);
        String resolvedExtension = resolveExtensionFromMimeType(mimeType);

        if (StringUtils.hasText(normalizedFileName)) {
            String currentExtension = extractExtension(normalizedFileName);
            if (StringUtils.hasText(currentExtension) && SUPPORTED_EXTENSIONS.contains(currentExtension)) {
                return normalizedFileName;
            }
            if (StringUtils.hasText(resolvedExtension)) {
                String baseName = stripExtension(normalizedFileName);
                return requireOriginalFilename(baseName + "." + resolvedExtension);
            }
            return requireOriginalFilename(normalizedFileName);
        }

        if (StringUtils.hasText(resolvedExtension)) {
            return REMOTE_FILE_BASENAME + "." + resolvedExtension;
        }
        throw new ClientException("无法识别 URL 文档类型", DOCUMENT_TYPE_NOT_SUPPORTED);
    }

    private String resolveExtensionFromMimeType(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return null;
        }
        return MIME_EXTENSION_MAPPING.get(normalizeMimeType(mimeType));
    }

    private String normalizeMimeType(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return null;
        }
        int separator = mimeType.indexOf(';');
        String normalized = separator >= 0 ? mimeType.substring(0, separator) : mimeType;
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    private String extractExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String stripExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return REMOTE_FILE_BASENAME;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }
}
