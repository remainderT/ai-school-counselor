package org.buaa.rag.module.ingestion;

import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_PARSE_FAILED;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.STORAGE_SERVICE_ERROR;
import static org.buaa.rag.common.enums.UploadStatusEnum.COMPLETED;
import static org.buaa.rag.common.enums.UploadStatusEnum.FAILED_FINAL;
import static org.buaa.rag.common.enums.UploadStatusEnum.PROCESSING;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.dao.entity.ChunkDO;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.mapper.ChunkMapper;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dto.ContentFragment;
import org.buaa.rag.module.parser.DocumentParseResult;
import org.buaa.rag.module.parser.DocumentParser;
import org.buaa.rag.module.parser.DocumentParserSelector;
import org.buaa.rag.module.parser.TextCleaningService;
import org.buaa.rag.module.vector.DocumentIndexingService;
import org.buaa.rag.module.vector.MilvusVectorStoreService;
import org.buaa.rag.properties.FIleParseProperties;
import org.buaa.rag.service.TextChunkingService;
import org.buaa.rag.tool.RustfsStorage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档离线摄取工作流
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIngestionWorkflow {

    private final RustfsStorage rustfsStorage;
    private final TextCleaningService textCleaningService;
    private final DocumentParserSelector documentParserSelector;
    private final TextChunkingService textChunkingService;
    private final DocumentIndexingService indexingService;
    private final MilvusVectorStoreService milvusVectorStoreService;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final FIleParseProperties fileParseProperties;
    private final DocumentIngestionFailureResolver failureResolver;

    private record IngestionPayload(List<ContentFragment> fragments, List<float[]> vectors) {
    }

    public void process(DocumentIngestionTask task) {
        DocumentDO document = loadDocument(task.documentMd5());
        if (document == null) {
            return;
        }
        if (!markProcessing(document)) {
            log.info("文档状态不可更新，跳过异步摄取: {}", task.documentMd5());
            return;
        }

        try {
            IngestionPayload payload = buildPayload(document, task.originalFileName());
            persistArtifacts(document, payload);
            markCompleted(document);
        } catch (Exception exception) {
            cleanupArtifacts(task.documentMd5());
            throw failureResolver.toIngestionException(exception);
        }
    }

    public void markFailed(String documentMd5, String failureReason) {
        cleanupArtifacts(documentMd5);
        updateStatus(documentMd5, FAILED_FINAL.getCode(), LocalDateTime.now(), failureReason);
    }

    public void cleanupArtifacts(String documentMd5) {
        try {
            indexingService.removeIndex(documentMd5);
        } catch (Exception e) {
            log.warn("回滚索引失败: {}", documentMd5, e);
        }
        try {
            milvusVectorStoreService.deleteByDocumentMd5(documentMd5);
        } catch (Exception e) {
            log.warn("回滚 Milvus 向量失败: {}", documentMd5, e);
        }
        try {
            deleteChunks(documentMd5);
        } catch (Exception e) {
            log.warn("回滚chunk失败: {}", documentMd5, e);
        }
    }

    private DocumentDO loadDocument(String documentMd5) {
        DocumentDO document = findByMd5(documentMd5);
        if (document == null) {
            log.info("文档记录不存在，跳过异步摄取: {}", documentMd5);
        }
        return document;
    }

    private IngestionPayload buildPayload(DocumentDO document, String storedFileName) {
        try (InputStream inputStream = openStoredDocument(document.getMd5Hash(), storedFileName)) {
            List<ContentFragment> fragments = extractAndPersistFragments(document.getMd5Hash(), storedFileName, inputStream);
            List<float[]> vectors = encodeFragments(fragments);
            return new IngestionPayload(fragments, vectors);
        } catch (IOException e) {
            throw new ServiceException(
                "关闭对象存储文件流失败: " + e.getMessage(),
                e,
                STORAGE_SERVICE_ERROR
            );
        }
    }

    private InputStream openStoredDocument(String documentMd5, String originalFileName) {
        String primaryPath = rustfsStorage.buildPrimaryPath(documentMd5, originalFileName);
        try {
            return rustfsStorage.download(primaryPath);
        } catch (Exception primaryException) {
            throw new ServiceException(
                "对象存储下载失败: " + primaryException.getMessage(),
                primaryException,
                STORAGE_SERVICE_ERROR
            );
        }
    }

    private List<ContentFragment> extractAndPersistFragments(String documentMd5,
                                                             String originalFileName,
                                                             InputStream inputStream) {
        String raw = extractText(inputStream, originalFileName, null);
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

        replaceChunks(documentMd5, chunks);
        return toFragments(chunks);
    }

    private String extractText(InputStream stream, String fileName, String mimeType) {
        DocumentParser parser = documentParserSelector.selectByMimeType(mimeType, fileName);
        if (parser == null) {
            throw new ServiceException("未找到可用文档解析器", DOCUMENT_PARSE_FAILED);
        }
        try {
            DocumentParseResult result = parser.parse(stream, fileName, mimeType, buildParserOptions());
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
        return options;
    }

    private List<float[]> encodeFragments(List<ContentFragment> fragments) {
        List<float[]> vectors = indexingService.encodeFragments(fragments);
        log.info("向量化完成，向量数: {}", vectors.size());
        return vectors;
    }

    private void persistArtifacts(DocumentDO document, IngestionPayload payload) {
        indexingService.index(document.getMd5Hash(), payload.fragments(), payload.vectors());
        milvusVectorStoreService.upsertDocument(document, payload.fragments(), payload.vectors());
    }

    private void replaceChunks(String documentMd5, List<String> chunks) {
        deleteChunks(documentMd5);
        persistChunks(documentMd5, chunks);
    }

    private void persistChunks(String documentMd5, List<String> chunks) {
        for (int index = 0; index < chunks.size(); index++) {
            ChunkDO chunk = new ChunkDO();
            chunk.setDocumentMd5(documentMd5);
            chunk.setFragmentIndex(index + 1);
            chunk.setTextData(chunks.get(index));
            chunkMapper.insert(chunk);
        }
        log.info("已保存 {} 个chunk", chunks.size());
    }

    private List<ContentFragment> toFragments(List<String> chunks) {
        List<ContentFragment> fragments = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            fragments.add(new ContentFragment(index + 1, chunks.get(index)));
        }
        return fragments;
    }

    private void deleteChunks(String documentMd5) {
        if (!StringUtils.hasText(documentMd5)) {
            return;
        }
        chunkMapper.delete(
            Wrappers.lambdaQuery(ChunkDO.class)
                .eq(ChunkDO::getDocumentMd5, documentMd5)
        );
        log.debug("已删除文档chunk: {}", documentMd5);
    }

    private boolean markProcessing(DocumentDO document) {
        return updateStatus(document, PROCESSING.getCode(), null, null);
    }

    private void markCompleted(DocumentDO document) {
        updateStatus(document, COMPLETED.getCode(), LocalDateTime.now(), null);
    }

    private boolean updateStatus(String md5, Integer status, LocalDateTime processedAt, String failureReason) {
        return updateStatus(findByMd5(md5), status, processedAt, failureReason);
    }

    private boolean updateStatus(DocumentDO document, Integer status, LocalDateTime processedAt, String failureReason) {
        if (document == null) {
            return false;
        }
        document.setProcessingStatus(status);
        document.setProcessedAt(processedAt);
        document.setFailureReason(failureResolver.normalizeFailureReason(failureReason));
        return documentMapper.updateById(document) > 0;
    }

    private DocumentDO findByMd5(String md5) {
        if (!StringUtils.hasText(md5)) {
            return null;
        }
        return documentMapper.selectOne(
            Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getMd5Hash, md5)
                .eq(DocumentDO::getDelFlag, 0)
                .last("limit 1")
        );
    }
}
