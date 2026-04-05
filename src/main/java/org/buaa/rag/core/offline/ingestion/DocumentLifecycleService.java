package org.buaa.rag.core.offline.ingestion;

import static org.buaa.rag.common.enums.UploadStatusEnum.COMPLETED;
import static org.buaa.rag.common.enums.UploadStatusEnum.FAILED_FINAL;
import static org.buaa.rag.common.enums.UploadStatusEnum.PENDING;
import static org.buaa.rag.common.enums.UploadStatusEnum.PROCESSING;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.service.impl.DocumentServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import lombok.RequiredArgsConstructor;

/**
 * 文档生命周期服务
 * <p>
 * 负责文档记录创建、查询以及状态迁移，避免状态逻辑分散在上传和消费侧。
 */
@Service
@RequiredArgsConstructor
public class DocumentLifecycleService {

    private final DocumentMapper documentMapper;

    public DocumentDO createPendingDocument(DocumentServiceImpl.UploadPayload payload,
                                            Long knowledgeId,
                                            String sourceUrl,
                                            Integer scheduleEnabled,
                                            String scheduleCron,
                                            LocalDateTime nextRefreshAt,
                                            String chunkMode) {
        DocumentDO record = new DocumentDO();
        record.setMd5Hash(payload.md5());
        record.setOriginalFileName(payload.originalFilename());
        record.setFileSizeBytes(payload.size());
        record.setProcessingStatus(PENDING.getCode());
        record.setUserId(UserContext.getUserId());
        record.setKnowledgeId(knowledgeId);
        record.setSourceUrl(sourceUrl);
        record.setScheduleEnabled(scheduleEnabled);
        record.setScheduleCron(scheduleCron);
        record.setChunkMode(chunkMode);
        record.setNextRefreshAt(nextRefreshAt);
        record.setLastRefreshAt(null);
        record.setFailureReason(null);
        documentMapper.insert(record);
        return record;
    }

    public DocumentDO findDocumentById(Long documentId) {
        if (documentId == null) {
            return null;
        }
        return documentMapper.selectOne(
            Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getId, documentId)
                .eq(DocumentDO::getDelFlag, 0)
                .last("limit 1")
        );
    }

    public DocumentDO findDocumentByMd5AndUserId(String md5, Long userId) {
        if (!StringUtils.hasText(md5) || userId == null) {
            return null;
        }
        return documentMapper.selectOne(
            Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getMd5Hash, md5)
                .eq(DocumentDO::getUserId, userId)
                .eq(DocumentDO::getDelFlag, 0)
                .last("limit 1")
        );
    }

    public boolean markProcessing(Long documentId) {
        // 仅允许待处理文档进入处理中，防止重复消费导致状态回退。
        return updateStatus(
            documentId,
            List.of(PENDING.getCode()),
            PROCESSING.getCode(),
            null,
            null
        );
    }

    public boolean markCompleted(Long documentId) {
        // 完成态必须从处理中迁移，避免“跳过处理直接完成”。
        return updateStatus(
            documentId,
            List.of(PROCESSING.getCode()),
            COMPLETED.getCode(),
            LocalDateTime.now(),
            null
        );
    }

    public boolean markFailed(Long documentId, Throwable throwable) {
        return markFailed(documentId, DocumentIngestionWorkflow.summarizeFailureReason(throwable));
    }

    public boolean markFailed(Long documentId, String failureReason) {
        // 失败态允许从待处理或处理中进入，兼容上传侧失败与异步消费失败两类场景。
        return updateStatus(
            documentId,
            List.of(PENDING.getCode(), PROCESSING.getCode()),
            FAILED_FINAL.getCode(),
            LocalDateTime.now(),
            failureReason
        );
    }

    private boolean updateStatus(Long documentId,
                                 Collection<Integer> allowedCurrentStatuses,
                                 Integer targetStatus,
                                 LocalDateTime processedAt,
                                 String failureReason) {
        if (documentId == null) {
            return false;
        }
        LambdaUpdateWrapper<DocumentDO> updateWrapper = Wrappers.lambdaUpdate(DocumentDO.class)
            .eq(DocumentDO::getId, documentId)
            .eq(DocumentDO::getDelFlag, 0);
        if (allowedCurrentStatuses != null && !allowedCurrentStatuses.isEmpty()) {
            updateWrapper.in(DocumentDO::getProcessingStatus, allowedCurrentStatuses);
        }
        // 以条件更新实现轻量级 CAS，返回值表示是否真正发生了状态迁移。
        updateWrapper
            .set(DocumentDO::getProcessingStatus, targetStatus)
            .set(DocumentDO::getProcessedAt, processedAt)
            .set(DocumentDO::getFailureReason, DocumentIngestionWorkflow.normalizeFailureReason(failureReason));
        return documentMapper.update(null, updateWrapper) > 0;
    }
}
