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
import static org.buaa.rag.common.enums.UploadStatusEnum.COMPLETED;
import static org.buaa.rag.common.enums.UploadStatusEnum.FAILED_FINAL;
import static org.buaa.rag.common.enums.UploadStatusEnum.PENDING;
import static org.buaa.rag.common.enums.UploadStatusEnum.PROCESSING;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.tika.Tika;
import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.DocumentIngestionException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dao.entity.ChunkDO;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dao.mapper.ChunkMapper;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.KnowledgeMapper;
import org.buaa.rag.dto.ContentFragment;
import org.buaa.rag.mq.IngestionProducer;
import org.buaa.rag.module.parser.TextCleaningService;
import org.buaa.rag.module.vector.DocumentIndexingService;
import org.buaa.rag.module.vector.MilvusVectorStoreService;
import org.buaa.rag.properties.FIleParseProperties;
import org.buaa.rag.service.DocumentService;
import org.buaa.rag.service.TextChunkingService;
import org.buaa.rag.module.parser.DocumentParseResult;
import org.buaa.rag.module.parser.DocumentParser;
import org.buaa.rag.module.parser.DocumentParserSelector;
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
 * 上传阶段仅负责校验、存储和入队；解析、分块与索引由 Redis Stream 异步完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, DocumentDO> implements DocumentService {

    private static final int MAGIC_HEADER_BYTES = 16;
    private static final int MAX_FAILURE_REASON_LENGTH = 512;

    private final RustfsStorage rustfsStorage;
    private final TextCleaningService textCleaningService;
    private final TextChunkingService textChunkingService;
    private final DocumentParserSelector documentParserSelector;
    private final DocumentIndexingService indexingService;
    private final ChunkMapper chunkMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final FIleParseProperties fileParseProperties;
    private final IngestionProducer producer;
    private final MilvusVectorStoreService milvusVectorStoreService;

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

        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            throw new ClientException("文件名不能为空", DOCUMENT_TYPE_NOT_SUPPORTED);
        }
        byte[] fileHeader = readFileHeader(file);
        String mimeType;
        try (InputStream in = file.getInputStream()) {
            mimeType = tika.detect(in, originalFilename);
        } catch (IOException e) {
            log.warn("MIME 检测失败: {}", e.getMessage());
            throw new ServiceException(DOCUMENT_MIME_FAILED);
        }
        FileTypeValidate.validate(originalFilename, mimeType, fileHeader);

        String md5;
        try {
            md5 = DigestUtils.md5Hex(file.getInputStream());
        } catch (IOException e) {
            throw new ServiceException("文档解析失败: " + e.getMessage(), e, DOCUMENT_PARSE_FAILED);
        }

        DocumentDO existing = baseMapper.selectOne(
                Wrappers.lambdaQuery(DocumentDO.class)
                        .eq(DocumentDO::getMd5Hash, md5)
                        .eq(DocumentDO::getUserId, UserContext.getUserId())
        );
        if (existing != null) {
            throw new ClientException(DOCUMENT_EXISTS);
        }

        try {
            rustfsStorage.upload(buildObjectPath(md5, originalFilename), file.getInputStream(), file.getSize(),
                    StringUtils.hasText(mimeType) ? mimeType : file.getContentType()
            );
            insertDocument(md5, originalFilename, file.getSize(), knowledge.getId());
            producer.enqueue(md5, originalFilename, 0);
        } catch (Exception e) {
            markFailed(md5, "文件上传失败", e);
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
        DocumentDO doc = baseMapper.selectById(id);
        if (doc == null || doc.getDelFlag() != 0) {
            throw new ClientException(DOCUMENT_NOT_EXISTS);
        }
        if (!doc.getUserId().equals(UserContext.getUserId())) {
            throw new ClientException(DOCUMENT_ACCESS_CONTROL_ERROR);
        }

        String md5 = doc.getMd5Hash();
        deleteFromStorage(md5, doc.getOriginalFileName());
        indexingService.removeIndex(md5);
        deleteFromMilvus(md5);
        deleteSegments(md5);
        baseMapper.deleteById(id);
    }

    @Override
    public void ingestDocument(String documentMd5, String originalFileName) {
        DocumentDO record = findByMd5(documentMd5);
        if (record == null) {
            log.info("文档记录不存在，跳过异步摄取: {}", documentMd5);
            return;
        }
        if (!markProcessing(documentMd5)) {
            log.info("文档状态不可更新，跳过异步摄取: {}", documentMd5);
            return;
        }

        try (InputStream in = downloadStoredDocument(documentMd5, originalFileName)) {
            List<ContentFragment> fragments = extractAndChunk(documentMd5, originalFileName, in);
            List<float[]> vectors = indexingService.encodeFragments(fragments);
            indexingService.index(documentMd5, fragments, vectors);
            milvusVectorStoreService.upsertDocument(record, fragments, vectors);
            markCompleted(documentMd5);
        } catch (Exception e) {
            rollbackIngestionArtifacts(documentMd5);
            throw toIngestionException(e);
        }
    }

    @Override
    public void markIngestionFinalFailure(String documentMd5, String failureReason) {
        rollbackIngestionArtifacts(documentMd5);
        updateStatus(documentMd5, FAILED_FINAL.getCode(), LocalDateTime.now(), failureReason);
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

    private void rollbackIngestionArtifacts(String documentMd5) {
        try {
            indexingService.removeIndex(documentMd5);
        } catch (Exception e) {
            log.warn("回滚索引失败: {}", documentMd5, e);
        }
        try {
            deleteFromMilvus(documentMd5);
        } catch (Exception e) {
            log.warn("回滚 Milvus 向量失败: {}", documentMd5, e);
        }
        try {
            deleteSegments(documentMd5);
        } catch (Exception e) {
            log.warn("回滚chunk失败: {}", documentMd5, e);
        }
    }

    private void deleteFromMilvus(String documentMd5) {
        milvusVectorStoreService.deleteByDocumentMd5(documentMd5);
    }

    private InputStream downloadStoredDocument(String documentMd5, String originalFileName) {
        String primaryPath = buildObjectPath(documentMd5, originalFileName);
        try {
            return rustfsStorage.download(primaryPath);
        } catch (Exception primaryException) {
            throw new DocumentIngestionException(
                "对象存储下载失败: " + primaryException.getMessage(),
                primaryException,
                true,
                STORAGE_SERVICE_ERROR
            );
        }
    }

    private DocumentIngestionException toIngestionException(Exception exception) {
        if (exception instanceof DocumentIngestionException ingestionException) {
            return ingestionException;
        }
        boolean retryable = isRetryableIngestionException(exception);
        if (exception instanceof ServiceException serviceException) {
            return new DocumentIngestionException(
                serviceException.getErrorMessage(),
                serviceException,
                retryable
            );
        }
        return new DocumentIngestionException(
            "文档处理失败: " + exception.getMessage(),
            exception,
            retryable
        );
    }

    private boolean isRetryableIngestionException(Throwable throwable) {
        if (throwable instanceof ClientException) {
            return false;
        }
        if (throwable instanceof ServiceException serviceException) {
            String errorCode = serviceException.getErrorCode();
            return !DOCUMENT_PARSE_FAILED.code().equals(errorCode)
                && !DOCUMENT_TYPE_NOT_SUPPORTED.code().equals(errorCode)
                && !DOCUMENT_SIZE_EXCEEDED.code().equals(errorCode)
                && !DOCUMENT_UPLOAD_NULL.code().equals(errorCode);
        }
        return true;
    }


    // ═══════════════════ 上传辅助 ═══════════════════


    private void insertDocument(String md5,
                                      String filename,
                                      long size,
                                      Long knowledgeId) {
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

    // ═══════════════════ 文档解析与分块 ═══════════════════

    /**
     * 提取文本 → 清洗 → 分块 → 持久化，返回片段列表供后续索引使用
     */
    private List<ContentFragment> extractAndChunk(String documentMd5,
                                                  String originalFileName,
                                                  InputStream in) {
        String raw = extractText(in, originalFileName, null);
        String cleaned = textCleaningService.clean(raw, fileParseProperties.getMaxCleanedChars());
        if (!StringUtils.hasText(cleaned)) {
            throw new ServiceException("文档内容为空或不可解析", DOCUMENT_PARSE_FAILED);
        }
        log.info("文本提取成功，字符数: {}", cleaned.length());

        List<String> chunks = textChunkingService.chunk(cleaned, fileParseProperties.getChunkSize());
        if (chunks.isEmpty()) {
            throw new ServiceException("文档切分后为空", DOCUMENT_PARSE_FAILED);
        }
        log.info("文本分块完成，片段数: {}", chunks.size());

        deleteSegments(documentMd5);
        persistSegments(documentMd5, chunks);

        return toFragments(chunks);
    }

    private String extractText(InputStream stream, String fileName, String mimeType) {
        DocumentParser parser = documentParserSelector.selectByMimeType(mimeType, fileName);
        if (parser == null) {
            throw new ServiceException("未找到可用文档解析器", DOCUMENT_PARSE_FAILED);
        }
        Map<String, Object> parserOptions = buildParserOptions();
        try {
            DocumentParseResult result = parser.parse(stream, fileName, mimeType, parserOptions);
            return result == null ? "" : result.text();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("文档解析错误: " + e.getMessage(), e, DOCUMENT_PARSE_FAILED);
        }
    }

    private Map<String, Object> buildParserOptions() {
        Map<String, Object> options = new HashMap<>();
        options.put("maxExtractedChars", fileParseProperties.getMaxExtractedChars());
        options.put("enableOcr", fileParseProperties.isEnableOcr());
        return options;
    }

    private void persistSegments(String documentMd5, List<String> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            ChunkDO chunk = new ChunkDO();
            chunk.setDocumentMd5(documentMd5);
            chunk.setFragmentIndex(i + 1);
            chunk.setTextData(chunks.get(i));
            chunkMapper.insert(chunk);
        }
        log.info("已保存 {} 个chunk", chunks.size());
    }

    private List<ContentFragment> toFragments(List<String> chunks) {
        List<ContentFragment> fragments = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            fragments.add(new ContentFragment(i + 1, chunks.get(i)));
        }
        return fragments;
    }

    // ═══════════════════ 存储操作 ═══════════════════

    private void deleteFromStorage(String md5, String filename) {
        String primaryPath = buildObjectPath(md5, filename);
        String legacyPath = buildLegacyObjectPath(md5, filename);
        try {
            rustfsStorage.delete(primaryPath);
            if (StringUtils.hasText(legacyPath) && !primaryPath.equals(legacyPath)) {
                // 兼容历史对象路径，双删避免遗留旧路径文件
                rustfsStorage.delete(legacyPath);
            }
        } catch (Exception e) {
            throw new ServiceException("对象存储删除失败: " + e.getMessage(), e, STORAGE_SERVICE_ERROR);
        }
    }

    private void deleteSegments(String documentMd5) {
        if (!StringUtils.hasText(documentMd5)) {
            return;
        }
        chunkMapper.delete(
            Wrappers.lambdaQuery(ChunkDO.class)
                .eq(ChunkDO::getDocumentMd5, documentMd5)
        );
        log.debug("已删除文档chunk: {}", documentMd5);
    }

    // ═══════════════════ 状态管理 ═══════════════════

    private boolean markProcessing(String md5) {
        return updateStatus(md5, PROCESSING.getCode(), null, null);
    }

    private void markCompleted(String md5) {
        updateStatus(md5, COMPLETED.getCode(), LocalDateTime.now(), null);
    }

    private void markFailed(String md5, String message, Exception error) {
        if (!StringUtils.hasText(md5)) {
            log.error(message, error);
            return;
        }
        log.error("{}: {}", message, md5, error);
        updateStatus(md5, FAILED_FINAL.getCode(), LocalDateTime.now(), summarizeFailureReason(error));
    }

    private boolean updateStatus(String md5, Integer status, LocalDateTime processedAt, String failureReason) {
        DocumentDO record = findByMd5(md5);
        if (record == null) {
            return false;
        }
        record.setProcessingStatus(status);
        record.setProcessedAt(processedAt);
        record.setFailureReason(normalizeFailureReason(failureReason));
        return baseMapper.updateById(record) > 0;
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

    private String buildObjectPath(String md5, String filename) {
        String extension = extractExtension(filename);
        String suffix = StringUtils.hasText(extension) ? "." + extension : "";
        return String.format("uploads/%s/source%s", md5, suffix);
    }

    private byte[] readFileHeader(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return inputStream.readNBytes(MAGIC_HEADER_BYTES);
        } catch (IOException e) {
            throw new ServiceException("文件头读取失败: " + e.getMessage(), e, DOCUMENT_PARSE_FAILED);
        }
    }

    private String summarizeFailureReason(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String prefix = root.getClass().getSimpleName();
        String summary = StringUtils.hasText(message) ? prefix + ": " + message : prefix;
        return normalizeFailureReason(summary);
    }

    private String normalizeFailureReason(String failureReason) {
        if (!StringUtils.hasText(failureReason)) {
            return null;
        }
        String value = failureReason.trim();
        if (value.length() <= MAX_FAILURE_REASON_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    private String extractExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String buildLegacyObjectPath(String md5, String filename) {
        if (!StringUtils.hasText(filename)) {
            return null;
        }
        return String.format("uploads/%s/%s", md5, filename);
    }

}
