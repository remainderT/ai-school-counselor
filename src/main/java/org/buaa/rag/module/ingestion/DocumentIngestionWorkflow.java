package org.buaa.rag.module.ingestion;

import java.util.List;

import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dto.ContentFragment;
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
    private final DocumentIngestionFailureResolver failureResolver;

    public void process(DocumentIngestionTask task) {
        DocumentDO document = loadDocument(task.documentId());
        if (document == null) {
            return;
        }
        if (!lifecycleService.markProcessing(document.getId())) {
            log.info("文档状态不可更新，跳过异步摄取: documentId={}", task.documentId());
            return;
        }

        try {
            List<ContentFragment> fragments = contentPipeline.extract(document);
            artifactService.replace(document, fragments);
            lifecycleService.markCompleted(document.getId());
        } catch (Exception exception) {
            artifactService.cleanup(document.getMd5Hash());
            throw failureResolver.toIngestionException(exception);
        }
    }

    public void markFailed(Long documentId, String failureReason) {
        DocumentDO document = loadDocument(documentId);
        if (document == null) {
            return;
        }
        artifactService.cleanup(document.getMd5Hash());
        lifecycleService.markFailed(documentId, failureReason);
    }

    private DocumentDO loadDocument(Long documentId) {
        DocumentDO document = lifecycleService.findActiveById(documentId);
        if (document == null) {
            log.info("文档记录不存在，跳过异步摄取: documentId={}", documentId);
        }
        return document;
    }
}
