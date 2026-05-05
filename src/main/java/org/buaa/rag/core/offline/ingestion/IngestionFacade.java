package org.buaa.rag.core.offline.ingestion;

import java.time.LocalDateTime;

import org.buaa.rag.core.model.UploadPayload;
import org.buaa.rag.core.mq.IngestionProducer;
import org.buaa.rag.core.offline.index.MilvusCollectionManager;
import org.buaa.rag.core.offline.parser.FileTypeValidate;
import org.buaa.rag.core.offline.parser.FileTypeValidate.InspectedFile;
import org.buaa.rag.dao.entity.DocumentDO;
import org.springframework.core.io.InputStreamSource;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

/**
 * 离线摄取门面：将 service 层的调用统一委托给内部核心类。
 */
@Service
@RequiredArgsConstructor
public class IngestionFacade {

    private final DocumentLifecycleService lifecycleService;
    private final DocumentArtifactService artifactService;
    private final IngestionProducer ingestionProducer;
    private final MilvusCollectionManager collectionManager;

    // ─────────────────── 文件类型校验 ───────────────────

    public InspectedFile inspectLocalFile(MultipartFile file) {
        return FileTypeValidate.inspectLocal(file);
    }

    public InspectedFile inspectRemoteFile(InputStreamSource source,
                                           String suggestedFileName,
                                           String fallbackMimeType,
                                           String defaultBaseName) {
        return FileTypeValidate.inspectRemote(source, suggestedFileName, fallbackMimeType, defaultBaseName);
    }

    // ─────────────────── 文档生命周期 ───────────────────

    public DocumentDO findDocumentByMd5AndUserId(String md5, Long userId) {
        return lifecycleService.findDocumentByMd5AndUserId(md5, userId);
    }

    public DocumentDO createPendingDocument(UploadPayload payload,
                                            Long knowledgeId,
                                            String sourceUrl,
                                            Integer scheduleEnabled,
                                            String scheduleCron,
                                            LocalDateTime nextRefreshAt,
                                            String chunkMode) {
        return lifecycleService.createPendingDocument(payload, knowledgeId, sourceUrl, scheduleEnabled, scheduleCron, nextRefreshAt, chunkMode);
    }

    public void markDocumentFailed(Long documentId, Exception error) {
        lifecycleService.markFailed(documentId, error);
    }

    // ─────────────────── 摄取任务入队 ───────────────────

    public RecordId enqueueIngestionTask(DocumentIngestionTask task) {
        return ingestionProducer.enqueue(task);
    }

    // ─────────────────── 产物管理 ───────────────────

    public void cleanupArtifacts(String bucketName, DocumentDO document) {
        artifactService.cleanup(bucketName, document);
    }

    public void updateSingleChunkIndex(String collectionName, DocumentDO document, int fragmentIndex, String newText) {
        artifactService.updateSingleChunkIndex(collectionName, document, fragmentIndex, newText);
    }

    public void deleteSingleChunkIndex(String collectionName, DocumentDO document, int fragmentIndex) {
        artifactService.deleteSingleChunkIndex(collectionName, document, fragmentIndex);
    }

    // ─────────────────── Milvus Collection 管理 ───────────────────

    public void ensureCollection(String collectionName) {
        collectionManager.ensureCollection(collectionName);
    }

    public void dropCollection(String collectionName) {
        collectionManager.dropCollection(collectionName);
    }
}
