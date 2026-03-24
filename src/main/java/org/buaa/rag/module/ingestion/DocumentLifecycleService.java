package org.buaa.rag.module.ingestion;

import static org.buaa.rag.common.enums.UploadStatusEnum.COMPLETED;
import static org.buaa.rag.common.enums.UploadStatusEnum.FAILED_FINAL;
import static org.buaa.rag.common.enums.UploadStatusEnum.PENDING;
import static org.buaa.rag.common.enums.UploadStatusEnum.PROCESSING;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
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
    private final DocumentIngestionFailureResolver failureResolver;

    public DocumentDO createPendingDocument(String md5,
                                            String originalFileName,
                                            long fileSizeBytes,
                                            Long knowledgeId,
                                            Long userId) {
        DocumentDO record = new DocumentDO();
        record.setMd5Hash(md5);
        record.setOriginalFileName(originalFileName);
        record.setFileSizeBytes(fileSizeBytes);
        record.setProcessingStatus(PENDING.getCode());
        record.setUserId(userId);
        record.setKnowledgeId(knowledgeId);
        record.setFailureReason(null);
        documentMapper.insert(record);
        return record;
    }

    public DocumentDO findActiveById(Long documentId) {
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

    public DocumentDO findActiveByMd5AndUserId(String md5, Long userId) {
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
        return updateStatus(
            documentId,
            List.of(PENDING.getCode()),
            PROCESSING.getCode(),
            null,
            null
        );
    }

    public boolean markCompleted(Long documentId) {
        return updateStatus(
            documentId,
            List.of(PROCESSING.getCode()),
            COMPLETED.getCode(),
            LocalDateTime.now(),
            null
        );
    }

    public boolean markFailed(Long documentId, Throwable throwable) {
        return markFailed(documentId, failureResolver.summarizeFailureReason(throwable));
    }

    public boolean markFailed(Long documentId, String failureReason) {
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
        updateWrapper
            .set(DocumentDO::getProcessingStatus, targetStatus)
            .set(DocumentDO::getProcessedAt, processedAt)
            .set(DocumentDO::getFailureReason, failureResolver.normalizeFailureReason(failureReason));
        return documentMapper.update(null, updateWrapper) > 0;
    }
}
