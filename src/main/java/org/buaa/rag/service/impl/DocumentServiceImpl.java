package org.buaa.rag.service.impl;

import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.DOCUMENT_ACCESS_CONTROL_ERROR;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.DOCUMENT_EXISTS;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.DOCUMENT_NOT_EXISTS;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.DOCUMENT_PARSE_FAILED;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.DOCUMENT_SIZE_EXCEEDED;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.DOCUMENT_UPLOAD_FAILED;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.DOCUMENT_UPLOAD_NULL;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.KNOWLEDGE_ACCESS_DENIED;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.KNOWLEDGE_ID_REQUIRED;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.KNOWLEDGE_NOT_EXISTS;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.codec.digest.DigestUtils;
import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.common.enums.UploadStatusEnum;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dao.entity.ChunkDO;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dao.mapper.ChunkMapper;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.KnowledgeMapper;
import org.buaa.rag.dto.req.ChunkPageReqDTO;
import org.buaa.rag.dto.req.DocumentPageReqDTO;
import org.buaa.rag.dto.req.DocumentUploadReqDTO;
import org.buaa.rag.dto.resp.DocumentDetailRespDTO;
import org.buaa.rag.dto.resp.DocumentPageRespDTO;
import org.buaa.rag.dto.resp.PageResponseDTO;
import org.buaa.rag.core.model.UploadPayload;
import org.buaa.rag.core.offline.ingestion.DocumentArtifactService;
import org.buaa.rag.core.offline.ingestion.DocumentLifecycleService;
import org.buaa.rag.core.offline.ingestion.DocumentIngestionTask;
import org.buaa.rag.core.offline.schedule.CronScheduleHelper;
import org.buaa.rag.tool.RemoteURLFetcher;
import org.buaa.rag.tool.RemoteURLFetcher.FetchedRemoteDocument;
import org.buaa.rag.core.mq.IngestionProducer;
import org.buaa.rag.properties.FileParseProperties;
import org.buaa.rag.service.DocumentService;
import org.buaa.rag.core.offline.parser.FileTypeValidate;
import org.buaa.rag.core.offline.parser.FileTypeValidate.InspectedFile;
import org.buaa.rag.tool.BucketManager;
import org.buaa.rag.tool.RustfsStorage;
import org.springframework.core.io.ByteArrayResource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.AllArgsConstructor;
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

    private static final String REMOTE_FILE_BASENAME = "remote-document";

    private final RustfsStorage rustfsStorage;
    private final KnowledgeMapper knowledgeMapper;
    private final ChunkMapper chunkMapper;
    private final FileParseProperties fileParseProperties;
    private final IngestionProducer ingestionProducer;
    private final DocumentLifecycleService lifecycleService;
    private final DocumentArtifactService artifactService;
    private final RemoteURLFetcher remoteURLFetcher;

    private static final String BASE_PATH = "/Users/yushuhao/Graduation/doc/Classification";
    private static final Map<String, String> DIR_TO_KB_NAME = new HashMap<>() {{
        put("教务教学", "academic_kb");
        put("学生事务与奖助", "affairs_kb");
        put("财务资产", "finance_kb");
        put("校园生活服务", "campus_life_kb");
        put("就业与职业发展", "career_kb");
        put("科创科研", "research_kb");
        put("心理与安全", "psy_safety_kb");
        put("综合", "integrated_kb");
        put("外事交流", "external_kb");
    }};

    @Value("${document.refresh.min-interval-seconds:60}")
    private long refreshMinIntervalSeconds;


    // UploadPayload 已提取到 org.buaa.rag.core.model.UploadPayload

    @Override
    public void upload(DocumentUploadReqDTO request) {
        if (request == null) {
            throw new ClientException(DOCUMENT_UPLOAD_NULL);
        }
        Long knowledgeId = request.getKnowledgeId();
        MultipartFile file = request.getFile();
        String url = request.getUrl() == null ? null : request.getUrl().trim();
        String chunkMode = normalizeChunkMode(request.getChunkMode());
        boolean isUrlSource = StringUtils.hasText(url) && (file == null || file.isEmpty());
        String scheduleCron = normalizeScheduleCron(request.getScheduleCron());
        boolean scheduleEnabled = Boolean.TRUE.equals(request.getScheduleEnabled());

        KnowledgeDO knowledge = verifyUploadKnowledge(knowledgeId);
        validateScheduleConfig(scheduleEnabled, scheduleCron, isUrlSource);
        LocalDateTime nextRefreshAt = resolveNextRefreshAt(scheduleEnabled, scheduleCron);
        UploadPayload payload = chooseUploadPayload(file, url);
        persistUploadPayload(payload, knowledge, chunkMode, isUrlSource ? url : null, scheduleEnabled, scheduleCron,
            nextRefreshAt);
    }

    @Override
    public List<DocumentDO> list(Long knowledgeId, String name) {
        var query = Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getUserId, UserContext.getUserId())
                .eq(DocumentDO::getDelFlag, 0)
                .eq(knowledgeId != null, DocumentDO::getKnowledgeId, knowledgeId)
                .like(StringUtils.hasText(name), DocumentDO::getOriginalFileName, name)
                .orderByDesc(DocumentDO::getCreateTime);
        List<DocumentDO> documents = baseMapper.selectList(query);
        documents.forEach(doc -> doc.setProcessingStatusDesc(UploadStatusEnum.descOf(doc.getProcessingStatus())));

        // 批量统计每个文档的 chunk 数量（只取 documentId 字段，避免加载完整 chunk 内容）
        if (!documents.isEmpty()) {
            List<Long> docIds = documents.stream().map(DocumentDO::getId).collect(Collectors.toList());
            Map<Long, Long> chunkCountMap = chunkMapper.selectList(
                Wrappers.lambdaQuery(ChunkDO.class)
                    .select(ChunkDO::getDocumentId)
                    .in(ChunkDO::getDocumentId, docIds)
                    .eq(ChunkDO::getDelFlag, 0)
            ).stream().collect(Collectors.groupingBy(ChunkDO::getDocumentId, Collectors.counting()));
            documents.forEach(doc -> doc.setChunkCount(chunkCountMap.getOrDefault(doc.getId(), 0L).intValue()));
        }

        return documents;
    }

    @Override
    public PageResponseDTO<DocumentPageRespDTO> pageList(DocumentPageReqDTO request) {
        long current = request.getCurrent() > 0 ? request.getCurrent() : 1L;
        long size = request.getSize() > 0 ? request.getSize() : 10L;
        Page<DocumentDO> page = new Page<>(current, size);
        IPage<DocumentDO> result = baseMapper.selectPage(page, Wrappers.lambdaQuery(DocumentDO.class)
            .eq(DocumentDO::getUserId, UserContext.getUserId())
            .eq(DocumentDO::getDelFlag, 0)
            .eq(request.getKnowledgeId() != null, DocumentDO::getKnowledgeId, request.getKnowledgeId())
            .like(StringUtils.hasText(request.getName()), DocumentDO::getOriginalFileName, request.getName())
            .orderByDesc(DocumentDO::getCreateTime));

        Map<Long, Integer> chunkCountMap = countChunksByDocumentIds(result.getRecords().stream()
            .map(DocumentDO::getId)
            .collect(Collectors.toList()));

        List<DocumentPageRespDTO> records = result.getRecords().stream()
            .map(doc -> DocumentPageRespDTO.builder()
                .id(doc.getId())
                .knowledgeId(doc.getKnowledgeId())
                .originalFileName(doc.getOriginalFileName())
                .fileSizeBytes(doc.getFileSizeBytes())
                .processingStatus(doc.getProcessingStatus())
                .processingStatusDesc(UploadStatusEnum.descOf(doc.getProcessingStatus()))
                .createTime(doc.getCreateTime())
                .chunkCount(chunkCountMap.getOrDefault(doc.getId(), 0))
                .build())
            .collect(Collectors.toList());

        return PageResponseDTO.<DocumentPageRespDTO>builder()
            .records(records)
            .total(result.getTotal())
            .size(result.getSize())
            .current(result.getCurrent())
            .pages(result.getPages())
            .build();
    }

    @Override
    public DocumentDetailRespDTO detail(Long documentId) {
        DocumentDO document = requireOwnedDocument(documentId);
        int chunkCount = countChunksByDocumentIds(Collections.singletonList(documentId)).getOrDefault(documentId, 0);
        return DocumentDetailRespDTO.builder()
            .id(document.getId())
            .knowledgeId(document.getKnowledgeId())
            .originalFileName(document.getOriginalFileName())
            .fileSizeBytes(document.getFileSizeBytes())
            .processingStatus(document.getProcessingStatus())
            .processingStatusDesc(UploadStatusEnum.descOf(document.getProcessingStatus()))
            .createTime(document.getCreateTime())
            .chunkCount(chunkCount)
            .sourceUrl(document.getSourceUrl())
            .scheduleEnabled(document.getScheduleEnabled())
            .scheduleCron(document.getScheduleCron())
            .chunkMode(document.getChunkMode())
            .nextRefreshAt(document.getNextRefreshAt())
            .lastRefreshAt(document.getLastRefreshAt())
            .failureReason(document.getFailureReason())
            .processedAt(document.getProcessedAt())
            .build();
    }

    @Override
    public byte[] download(Long documentId) {
        DocumentDO document = requireOwnedDocument(documentId);
        String bucketName = resolveKnowledgeBucketName(document.getKnowledgeId());
        try (InputStream inputStream = rustfsStorage.downloadPrimary(bucketName, document.getMd5Hash(), document.getOriginalFileName())) {
            return inputStream.readAllBytes();
        } catch (Exception e) {
            throw new ServiceException("文档下载失败: " + e.getMessage(), e, DOCUMENT_NOT_EXISTS);
        }
    }

    @Override
    public InputStream downloadStream(Long documentId) {
        DocumentDO document = requireOwnedDocument(documentId);
        String bucketName = resolveKnowledgeBucketName(document.getKnowledgeId());
        try {
            return rustfsStorage.downloadPrimary(bucketName, document.getMd5Hash(), document.getOriginalFileName());
        } catch (Exception e) {
            throw new ServiceException("文档下载失败: " + e.getMessage(), e, DOCUMENT_NOT_EXISTS);
        }
    }

    @Override
    public PageResponseDTO<ChunkDO> pageChunks(Long documentId, ChunkPageReqDTO request) {
        requireOwnedDocument(documentId);
        long current = request.getCurrent() > 0 ? request.getCurrent() : 1L;
        long size = request.getSize() > 0 ? request.getSize() : 10L;
        Page<ChunkDO> page = new Page<>(current, size);
        IPage<ChunkDO> result = chunkMapper.selectPage(page, Wrappers.lambdaQuery(ChunkDO.class)
            .eq(ChunkDO::getDocumentId, documentId)
            .eq(ChunkDO::getDelFlag, 0)
            .like(StringUtils.hasText(request.getKeyword()), ChunkDO::getTextData, request.getKeyword())
            .orderByAsc(ChunkDO::getFragmentIndex));
        return PageResponseDTO.<ChunkDO>builder()
            .records(result.getRecords())
            .total(result.getTotal())
            .size(result.getSize())
            .current(result.getCurrent())
            .pages(result.getPages())
            .build();
    }

    @Override
    public List<ChunkDO> listChunks(Long documentId) {
        requireOwnedDocument(documentId);
        return listChunksInternal(documentId);
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

        String bucketName = resolveKnowledgeBucketName(document.getKnowledgeId());
        rustfsStorage.delete(bucketName, document.getMd5Hash(), document.getOriginalFileName());
        artifactService.cleanup(bucketName, document);
        baseMapper.update(
            null,
            Wrappers.lambdaUpdate(DocumentDO.class)
                .eq(DocumentDO::getId, document.getId())
                .eq(DocumentDO::getDelFlag, 0)
                .set(DocumentDO::getDelFlag, 1)
        );
    }

    private UploadPayload buildMultipartUploadPayload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ClientException(DOCUMENT_UPLOAD_NULL);
        }
        if (file.getSize() > fileParseProperties.getMaxUploadBytes()) {
            throw new ClientException(DOCUMENT_SIZE_EXCEEDED);
        }
        String md5;
        try (InputStream inputStream = file.getInputStream()) {
            md5 = DigestUtils.md5Hex(inputStream);
        } catch (IOException e) {
            throw new ServiceException("文档解析失败: " + e.getMessage(), e, DOCUMENT_PARSE_FAILED);
        }

        InspectedFile inspectedFile = FileTypeValidate.inspectLocal(file);
        return new UploadPayload(inspectedFile.fileName(), inspectedFile.mimeType(), file.getSize(), md5, file);
    }

    @Override
    public String fullImport() {
        File baseDir = new File(BASE_PATH);
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            return "目录不存在: " + BASE_PATH;
        }

        int success = 0;
        int failed = 0;
        int total = 0;

        File[] subDirs = baseDir.listFiles(File::isDirectory);
        if (subDirs == null) return "无子目录";

        for (File dir : subDirs) {
            String dirName = dir.getName();
            String kbName = DIR_TO_KB_NAME.get(dirName);
            if (kbName == null) {
                log.warn("跳过未知目录: {}", dirName);
                continue;
            }

            KnowledgeDO knowledge = knowledgeMapper.selectOne(
                Wrappers.lambdaQuery(KnowledgeDO.class)
                    .eq(KnowledgeDO::getName, kbName)
                    .eq(KnowledgeDO::getDelFlag, 0)
            );

            if (knowledge == null) {
                log.error("知识库未找到: {}", kbName);
                continue;
            }

            File[] files = dir.listFiles(f -> !f.isDirectory() && !f.getName().startsWith("."));
            if (files == null) continue;

            for (File f : files) {
                total++;
                try {
                    String contentType = Files.probeContentType(f.toPath());
                    if (contentType == null) contentType = "application/octet-stream";

                    MultipartFile multipartFile = LocalFileMultipartFile.fromFile(f, contentType);

                    DocumentUploadReqDTO req = new DocumentUploadReqDTO();
                    req.setKnowledgeId(knowledge.getId());
                    req.setFile(multipartFile);

                    this.upload(req);
                    success++;
                } catch (Exception e) {
                    log.error("上传文件失败: {}, 错误: {}", f.getName(), e.getMessage());
                    failed++;
                }
            }
        }

        return String.format("全量导入完成: 总计 %d, 成功 %d, 失败 %d", total, success, failed);
    }

    @AllArgsConstructor
    private static class LocalFileMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] content;

        public static LocalFileMultipartFile fromFile(File file, String contentType) throws IOException {
            return new LocalFileMultipartFile(
                "file",
                file.getName(),
                contentType,
                Files.readAllBytes(file.toPath())
            );
        }

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content == null || content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() throws IOException { return content; }
        @Override public InputStream getInputStream() throws IOException { return new ByteArrayInputStream(content); }
        @Override public void transferTo(File dest) throws IOException, IllegalStateException { Files.write(dest.toPath(), content); }
    }

    private UploadPayload buildUrlUploadPayload(String sourceUrl) {
        FetchedRemoteDocument remoteDocument = remoteURLFetcher.fetch(sourceUrl, fileParseProperties.getMaxUploadBytes());
        byte[] body = remoteDocument.body();
        if (body == null || body.length == 0) {
            throw new ClientException(DOCUMENT_UPLOAD_NULL);
        }
        ByteArrayResource source = new ByteArrayResource(body);
        InspectedFile inspectedFile = FileTypeValidate.inspectRemote(
            source,
            remoteDocument.fileName(),
            remoteDocument.contentType(),
            REMOTE_FILE_BASENAME);
        String md5 = DigestUtils.md5Hex(body);
        return new UploadPayload(inspectedFile.fileName(), inspectedFile.mimeType(), body.length, md5, source);
    }

    private UploadPayload chooseUploadPayload(MultipartFile file, String url) {
        if (file != null && !file.isEmpty()) {
            return buildMultipartUploadPayload(file);
        }
        if (StringUtils.hasText(url)) {
            return buildUrlUploadPayload(url);
        }
        throw new ClientException(DOCUMENT_UPLOAD_NULL);
    }

    private Map<Long, Integer> countChunksByDocumentIds(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return chunkMapper.selectList(
            Wrappers.lambdaQuery(ChunkDO.class)
                .select(ChunkDO::getDocumentId)
                .in(ChunkDO::getDocumentId, documentIds)
                .eq(ChunkDO::getDelFlag, 0)
        ).stream().collect(Collectors.groupingBy(ChunkDO::getDocumentId,
            Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
    }

    private DocumentDO requireOwnedDocument(Long documentId) {
        DocumentDO document = baseMapper.selectById(documentId);
        if (document == null || document.getDelFlag() != 0) {
            throw new ClientException(DOCUMENT_NOT_EXISTS);
        }
        if (!document.getUserId().equals(UserContext.getUserId())) {
            throw new ClientException(DOCUMENT_ACCESS_CONTROL_ERROR);
        }
        return document;
    }

    private List<ChunkDO> listChunksInternal(Long documentId) {
        return chunkMapper.selectList(
            Wrappers.lambdaQuery(ChunkDO.class)
                .eq(ChunkDO::getDocumentId, documentId)
                .eq(ChunkDO::getDelFlag, 0)
                .orderByAsc(ChunkDO::getFragmentIndex)
        );
    }

    private void persistUploadPayload(UploadPayload payload,
                                      KnowledgeDO knowledge,
                                      String chunkMode,
                                      String sourceUrl,
                                      boolean scheduleEnabled,
                                      String scheduleCron,
                                      LocalDateTime nextRefreshAt) {
        DocumentDO document = lifecycleService.findDocumentByMd5AndUserId(payload.md5(), UserContext.getUserId());
        if (document != null) {
            throw new ClientException(DOCUMENT_EXISTS);
        }
        String bucketName = BucketManager.toBucketName(knowledge.getName());
        try {
            rustfsStorage.upload(bucketName, payload);
            document = lifecycleService.createPendingDocument(
                payload,
                knowledge.getId(),
                sourceUrl,
                scheduleEnabled ? 1 : 0,
                scheduleEnabled ? scheduleCron : null,
                scheduleEnabled ? nextRefreshAt : null,
                chunkMode);
            // 通过任务消息触发离线摄取，接口可快速返回。
            DocumentIngestionTask task = DocumentIngestionTask.initial(document.getId(), chunkMode);
            ingestionProducer.enqueue(task);
        } catch (Exception e) {
            // 文档记录尚未落库时，尝试清理已上传的对象存储文件，避免孤儿文件。
            if (document == null) {
                rustfsStorage.delete(bucketName, payload.md5(), payload.originalFilename());
            }
            markUploadFailure(document, e);
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

    /**
     * 根据知识库 ID 查询对应的 bucketName，找不到时返回空字符串并打印告警。
     * bucketName 由 knowledge.name 动态计算，不存入数据库。
     */
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
        return BucketManager.toBucketName(knowledge.getName());
    }

    private void markUploadFailure(DocumentDO document, Exception error) {
        if (document == null || document.getId() == null) {
            log.error("文件上传失败，未能建立文档记录", error);
            return;
        }
        // 有文档记录时统一落失败态，便于前端和运维定位失败原因。
        log.error("文件上传失败: documentId={}", document.getId(), error);
        lifecycleService.markFailed(document.getId(), error);
    }

    private String normalizeChunkMode(String chunkMode) {
        if (!StringUtils.hasText(chunkMode)) {
            return fileParseProperties.getChunkMode();
        }
        return chunkMode.trim();
    }

    private String normalizeScheduleCron(String scheduleCron) {
        if (!StringUtils.hasText(scheduleCron)) {
            return null;
        }
        return scheduleCron.trim();
    }

    private void validateScheduleConfig(boolean scheduleEnabled, String scheduleCron, boolean isUrlSource) {
        if (!scheduleEnabled) {
            return;
        }
        if (!isUrlSource) {
            throw new ClientException("仅 URL 上传支持定时更新");
        }
        if (!StringUtils.hasText(scheduleCron)) {
            throw new ClientException("定时表达式不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean intervalTooShort;
        LocalDateTime nextRunTime;
        try {
            intervalTooShort = CronScheduleHelper.isIntervalTooShort(scheduleCron, now, refreshMinIntervalSeconds);
            nextRunTime = CronScheduleHelper.nextRunTime(scheduleCron, now);
        } catch (Exception e) {
            throw new ClientException("定时表达式不合法");
        }
        if (intervalTooShort) {
            throw new ClientException("定时周期不能小于 " + refreshMinIntervalSeconds + " 秒");
        }
        if (nextRunTime == null) {
            throw new ClientException("无法计算下一次执行时间");
        }
    }

    private LocalDateTime resolveNextRefreshAt(boolean scheduleEnabled, String scheduleCron) {
        if (!scheduleEnabled) {
            return null;
        }
        return CronScheduleHelper.nextRunTime(scheduleCron, LocalDateTime.now());
    }
}
