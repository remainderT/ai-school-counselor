package org.buaa.rag.core.offline.ingestion;

import java.util.List;

import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dao.mapper.KnowledgeMapper;
import org.buaa.rag.core.model.ContentFragment;
import org.buaa.rag.tool.KnowledgeNameConverter;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档离线摄取工作流
 * <p>
 * 仅负责编排生命周期、内容提取与产物写入，具体实现下沉到专门组件。
 * <ul>
 *   <li>RustFS Bucket 名：{@link KnowledgeNameConverter#toBucketName}(knowledge.name)，下划线转连字符</li>
 *   <li>Milvus Collection 名：{@link KnowledgeNameConverter#toCollectionName}(knowledge.name)，连字符转下划线</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIngestionWorkflow {

    private final DocumentLifecycleService lifecycleService;
    private final DocumentContentPipeline contentPipeline;
    private final DocumentArtifactService artifactService;
    private final KnowledgeMapper knowledgeMapper;

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

        StorageNames names = resolveStorageNames(document);
        try {
            List<ContentFragment> fragments = contentPipeline.extract(document, task.chunkMode(), names.bucketName());
            artifactService.replace(names.collectionName(), document, fragments);
            lifecycleService.markCompleted(document.getId());
        } catch (Exception exception) {
            artifactService.cleanup(names.collectionName(), document);
            throw IngestionExceptionUtils.toIngestionException(exception);
        }
    }

    public void markFailed(Long documentId, String failureReason) {
        DocumentDO document = loadDocument(documentId);
        if (document == null) {
            return;
        }
        StorageNames names = resolveStorageNames(document);
        artifactService.cleanup(names.collectionName(), document);
        lifecycleService.markFailed(documentId, failureReason);
    }

    /**
     * 解析知识库存储名称，返回 RustFS Bucket 名和 Milvus Collection 名。
     * 找不到知识库时两个名称均为空字符串（仅记录告警）。
     */
    private StorageNames resolveStorageNames(DocumentDO document) {
        if (document.getKnowledgeId() == null) {
            log.warn("文档未关联知识库，无法解析存储名称: documentId={}", document.getId());
            return StorageNames.empty();
        }
        KnowledgeDO knowledge = knowledgeMapper.selectOne(
            Wrappers.lambdaQuery(KnowledgeDO.class)
                .eq(KnowledgeDO::getId, document.getKnowledgeId())
                .eq(KnowledgeDO::getDelFlag, 0)
        );
        if (knowledge == null) {
            log.warn("知识库未找到: knowledgeId={}", document.getKnowledgeId());
            return StorageNames.empty();
        }
        return StorageNames.of(knowledge.getName());
    }

    private DocumentDO loadDocument(Long documentId) {
        DocumentDO document = lifecycleService.findDocumentById(documentId);
        if (document == null) {
            log.info("文档记录不存在，跳过异步摄取: documentId={}", documentId);
        }
        return document;
    }

    /**
     * 知识库存储资源名称对：RustFS Bucket 名（连字符）+ Milvus Collection 名（下划线）。
     */
    private record StorageNames(String bucketName, String collectionName) {
        static StorageNames of(String kbName) {
            return new StorageNames(KnowledgeNameConverter.toBucketName(kbName), KnowledgeNameConverter.toCollectionName(kbName));
        }
        static StorageNames empty() {
            return new StorageNames("", "");
        }
    }
}
