package org.buaa.rag.core.offline.ingestion;

import java.util.List;

import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.core.model.ContentFragment;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档离线摄取工作流
 * <p>
 * 仅负责编排生命周期、内容提取与产物写入，具体实现下沉到专门组件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIngestionWorkflow {

    private final DocumentLifecycleService lifecycleService;
    private final DocumentContentPipeline contentPipeline;
    private final DocumentArtifactService artifactService;

    public void process(DocumentIngestionTask task) {
        DocumentDO document = loadDocument(task.documentId());
        if (document == null) {
            return;
        }
        // 通过状态机门禁避免重复消费：仅允许 PENDING -> PROCESSING 的单向迁移。
        if (!lifecycleService.markProcessing(document.getId())) {
            log.info("文档状态不可更新，跳过异步摄取: documentId={}", task.documentId());
            return;
        }

        try {
            List<ContentFragment> fragments = contentPipeline.extract(document, task.chunkMode());
            artifactService.replace(document, fragments);
            lifecycleService.markCompleted(document.getId());
        } catch (Exception exception) {
            // 异步链路采用“失败即清理”策略，尽量避免 chunk/ES/Milvus 部分成功造成脏数据。
            artifactService.cleanup(document);
            throw IngestionExceptionUtils.toIngestionException(exception);
        }
    }

    public void markFailed(Long documentId, String failureReason) {
        DocumentDO document = loadDocument(documentId);
        if (document == null) {
            return;
        }
        // 达到重试上限后由消费者调用，统一走清理+失败落库。
        artifactService.cleanup(document);
        lifecycleService.markFailed(documentId, failureReason);
    }

    private DocumentDO loadDocument(Long documentId) {
        DocumentDO document = lifecycleService.findDocumentById(documentId);
        if (document == null) {
            log.info("文档记录不存在，跳过异步摄取: documentId={}", documentId);
        }
        return document;
    }


}
