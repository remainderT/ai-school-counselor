package org.buaa.rag.core.offline.schedule;

import static org.buaa.rag.common.enums.UploadStatusEnum.COMPLETED;
import static org.buaa.rag.common.enums.UploadStatusEnum.FAILED_FINAL;
import static org.buaa.rag.common.enums.UploadStatusEnum.PENDING;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;
import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.common.enums.BaseErrorCode;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.KnowledgeMapper;
import org.buaa.rag.core.offline.ingestion.DocumentIngestionTask;
import org.buaa.rag.core.offline.ingestion.DocumentLifecycleService;
import org.buaa.rag.core.offline.parser.FileTypeValidate;
import org.buaa.rag.core.offline.parser.FileTypeValidate.InspectedFile;
import org.buaa.rag.core.model.UploadPayload;
import org.buaa.rag.core.mq.IngestionProducer;
import org.buaa.rag.properties.FileParseProperties;
import org.buaa.rag.tool.RemoteURLFetcher;
import org.buaa.rag.tool.RemoteURLFetcher.FetchedRemoteDocument;
import org.buaa.rag.tool.KnowledgeNameConverter;
import org.buaa.rag.tool.RustfsStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * URL 文档定时刷新任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentRefreshScheduler {

    private static final String REMOTE_FILE_BASENAME = "remote-document";

    private final DocumentMapper documentMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final DocumentLifecycleService lifecycleService;
    private final RemoteURLFetcher remoteURLFetcher;
    private final RustfsStorage rustfsStorage;
    private final IngestionProducer ingestionProducer;
    private final FileParseProperties fileParseProperties;

    @Value("${document.refresh.batch-size:20}")
    private int batchSize;

    @Value("${document.refresh.min-interval-seconds:60}")
    private long refreshMinIntervalSeconds;

    @Scheduled(fixedDelayString = "${document.refresh.scan-delay-ms:10000}")
    public void scan() {
        LocalDateTime now = LocalDateTime.now();
        List<DocumentDO> dueDocuments = documentMapper.selectList(
            Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getDelFlag, 0)
                .eq(DocumentDO::getScheduleEnabled, 1)
                .isNotNull(DocumentDO::getSourceUrl)
                .le(DocumentDO::getNextRefreshAt, now)
                .orderByAsc(DocumentDO::getNextRefreshAt)
                .last("limit " + Math.max(1, batchSize))
        );
        if (dueDocuments == null || dueDocuments.isEmpty()) {
            return;
        }
        for (DocumentDO document : dueDocuments) {
            try {
                refreshOne(document, now);
            } catch (Exception e) {
                log.error("URL 定时刷新异常: documentId={}", document.getId(), e);
            }
        }
    }

    private void refreshOne(DocumentDO document, LocalDateTime now) {
        if (document == null || document.getId() == null) {
            return;
        }
        String cron = normalizeCron(document.getScheduleCron());
        if (!StringUtils.hasText(cron) || !StringUtils.hasText(document.getSourceUrl())) {
            disableSchedule(document.getId(), "URL 或定时表达式为空，已自动关闭定时更新");
            return;
        }
        LocalDateTime nextRunAt;
        try {
            nextRunAt = resolveNextRun(cron, now);
        } catch (ClientException e) {
            disableSchedule(document.getId(), e.getErrorMessage());
            return;
        }
        if (nextRunAt == null) {
            disableSchedule(document.getId(), "无法计算下一次执行时间，已自动关闭定时更新");
            return;
        }
        if (!isRefreshableStatus(document.getProcessingStatus())) {
            touchNextRun(document.getId(), now, nextRunAt, null);
            return;
        }

        FetchedRemoteDocument remoteDocument = remoteURLFetcher.fetch(document.getSourceUrl(),
            fileParseProperties.getMaxUploadBytes());
        byte[] body = remoteDocument.body();
        if (body == null || body.length == 0) {
            touchNextRun(document.getId(), now, nextRunAt, "定时拉取内容为空");
            return;
        }
        InspectedFile inspectedFile = FileTypeValidate.inspectRemote(
            new ByteArrayResource(body),
            remoteDocument.fileName(),
            remoteDocument.contentType(),
            REMOTE_FILE_BASENAME);
        String newMd5 = DigestUtils.md5Hex(body);
        if (newMd5.equals(document.getMd5Hash())) {
            touchNextRun(document.getId(), now, nextRunAt, null);
            return;
        }

        DocumentDO duplicated = lifecycleService.findDocumentByMd5AndUserId(newMd5, document.getUserId());
        if (duplicated != null && !duplicated.getId().equals(document.getId())) {
            touchNextRun(document.getId(), now, nextRunAt, "命中同用户重复内容，跳过本次更新");
            return;
        }

        String chunkMode = StringUtils.hasText(document.getChunkMode())
            ? document.getChunkMode()
            : fileParseProperties.getChunkMode();
        UploadPayload payload = new UploadPayload(
            inspectedFile.fileName(),
            inspectedFile.mimeType(),
            body.length,
            newMd5,
            new ByteArrayResource(body)
        );
        String bucketName = resolveKnowledgeBucketName(document.getKnowledgeId());
        rustfsStorageUpload(bucketName, payload, document.getId(), nextRunAt, now);
        int updated = documentMapper.update(
            null,
            Wrappers.lambdaUpdate(DocumentDO.class)
                .eq(DocumentDO::getId, document.getId())
                .eq(DocumentDO::getDelFlag, 0)
                .in(DocumentDO::getProcessingStatus, COMPLETED.getCode(), FAILED_FINAL.getCode())
                .set(DocumentDO::getMd5Hash, newMd5)
                .set(DocumentDO::getOriginalFileName, inspectedFile.fileName())
                .set(DocumentDO::getFileSizeBytes, body.length)
                .set(DocumentDO::getProcessingStatus, PENDING.getCode())
                .set(DocumentDO::getProcessedAt, null)
                .set(DocumentDO::getFailureReason, null)
                .set(DocumentDO::getChunkMode, chunkMode)
                .set(DocumentDO::getLastRefreshAt, now)
                .set(DocumentDO::getNextRefreshAt, nextRunAt)
        );
        if (updated <= 0) {
            return;
        }
        ingestionProducer.enqueue(DocumentIngestionTask.initial(document.getId(), chunkMode));
        log.info("URL 定时刷新已入队: documentId={}, oldMd5={}, newMd5={}", document.getId(), document.getMd5Hash(),
            newMd5);
    }

    private void rustfsStorageUpload(String bucketName,
                                     UploadPayload payload,
                                     Long documentId,
                                     LocalDateTime nextRunAt,
                                     LocalDateTime now) {
        try {
            rustfsStorage.upload(bucketName, payload);
        } catch (Exception e) {
            touchNextRun(documentId, now, nextRunAt, "上传刷新文件失败: " + e.getMessage());
            throw new ServiceException("上传刷新文件失败: " + e.getMessage(), e, BaseErrorCode.SERVICE_ERROR);
        }
    }

    private String resolveKnowledgeBucketName(Long knowledgeId) {
        if (knowledgeId == null) {
            return "";
        }
        KnowledgeDO knowledge = knowledgeMapper.selectOne(
            Wrappers.lambdaQuery(KnowledgeDO.class)
                .eq(KnowledgeDO::getId, knowledgeId)
                .eq(KnowledgeDO::getDelFlag, 0)
        );
        if (knowledge == null) {
            log.warn("知识库未找到: knowledgeId={}", knowledgeId);
            return "";
        }
        return KnowledgeNameConverter.toBucketName(knowledge.getName());
    }

    private LocalDateTime resolveNextRun(String cron, LocalDateTime now) {
        try {
            if (CronScheduleHelper.isIntervalTooShort(cron, now, refreshMinIntervalSeconds)) {
                throw new ClientException("定时周期不能小于 " + refreshMinIntervalSeconds + " 秒");
            }
            return CronScheduleHelper.nextRunTime(cron, now);
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("定时表达式不合法");
        }
    }

    private void disableSchedule(Long documentId, String reason) {
        documentMapper.update(
            null,
            Wrappers.lambdaUpdate(DocumentDO.class)
                .eq(DocumentDO::getId, documentId)
                .eq(DocumentDO::getDelFlag, 0)
                .set(DocumentDO::getScheduleEnabled, 0)
                .set(DocumentDO::getNextRefreshAt, null)
                .set(DocumentDO::getFailureReason, reason)
        );
    }

    private void touchNextRun(Long documentId, LocalDateTime now, LocalDateTime nextRunAt, String failureReason) {
        documentMapper.update(
            null,
            Wrappers.lambdaUpdate(DocumentDO.class)
                .eq(DocumentDO::getId, documentId)
                .eq(DocumentDO::getDelFlag, 0)
                .set(DocumentDO::getLastRefreshAt, now)
                .set(DocumentDO::getNextRefreshAt, nextRunAt)
                .set(DocumentDO::getFailureReason, failureReason)
        );
    }

    private String normalizeCron(String cron) {
        if (!StringUtils.hasText(cron)) {
            return null;
        }
        return cron.trim();
    }

    private boolean isRefreshableStatus(Integer status) {
        if (status == null) {
            return false;
        }
        return status == COMPLETED.getCode() || status == FAILED_FINAL.getCode();
    }
}
