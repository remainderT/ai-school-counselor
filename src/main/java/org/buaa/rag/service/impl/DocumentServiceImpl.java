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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;
import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.common.enums.UploadStatusEnum;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.KnowledgeMapper;
import org.buaa.rag.dto.req.DocumentUploadReqDTO;
import org.buaa.rag.core.offline.ingestion.DocumentArtifactService;
import org.buaa.rag.core.offline.ingestion.DocumentLifecycleService;
import org.buaa.rag.core.offline.ingestion.DocumentIngestionTask;
import org.buaa.rag.tool.RemoteURLFetcher;
import org.buaa.rag.tool.RemoteURLFetcher.FetchedRemoteDocument;
import org.buaa.rag.core.mq.IngestionProducer;
import org.buaa.rag.properties.FIleParseProperties;
import org.buaa.rag.service.DocumentService;
import org.buaa.rag.core.offline.parser.FileTypeValidate;
import org.buaa.rag.core.offline.parser.FileTypeValidate.InspectedFile;
import org.buaa.rag.tool.RustfsStorage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamSource;
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
 * 上传阶段仅负责校验、存储、建档和入队；离线摄取由独立模块处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, DocumentDO> implements DocumentService {

    private static final String REMOTE_FILE_BASENAME = "remote-document";

    private final RustfsStorage rustfsStorage;
    private final KnowledgeMapper knowledgeMapper;
    private final FIleParseProperties fileParseProperties;
    private final IngestionProducer ingestionProducer;
    private final DocumentLifecycleService lifecycleService;
    private final DocumentArtifactService artifactService;
    private final RemoteURLFetcher remoteURLFetcher;


    public record UploadPayload(String originalFilename,
                                 String mimeType,
                                 long size,
                                 String md5,
                                 InputStreamSource source) {
    }

    @Override
    public void upload(DocumentUploadReqDTO request) {
        if (request == null) {
            throw new ClientException(DOCUMENT_UPLOAD_NULL);
        }
        Long knowledgeId = request.getKnowledgeId();
        MultipartFile file = request.getFile();
        String url = request.getUrl();
        String chunkMode = request.getChunkMode();

        verifyUploadKnowledge(knowledgeId);
        UploadPayload payload = chooseUploadPayload(file, url);
        persistUploadPayload(payload, knowledgeId, chunkMode);
    }

    @Override
    public List<DocumentDO> list() {
        List<DocumentDO> documents = baseMapper.selectList(
            Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getUserId, UserContext.getUserId())
                .eq(DocumentDO::getDelFlag, 0)
        );
        documents.forEach(doc -> doc.setProcessingStatusDesc(UploadStatusEnum.descOf(doc.getProcessingStatus())));
        return documents;
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

        rustfsStorage.delete(document.getMd5Hash(), document.getOriginalFileName());
        artifactService.cleanup(document);
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

    private void persistUploadPayload(UploadPayload payload, Long knowledgeId, String chunkMode) {
        DocumentDO document = lifecycleService.findDocumentByMd5AndUserId(payload.md5, UserContext.getUserId());
        if (document != null) {
            throw new ClientException(DOCUMENT_EXISTS);
        }
        try {
            rustfsStorage.upload(payload);
            document = lifecycleService.createPendingDocument(payload, knowledgeId);
            // 通过任务消息触发离线摄取，接口可快速返回。
            DocumentIngestionTask task = DocumentIngestionTask.initial(document.getId(), chunkMode);
            ingestionProducer.enqueue(task);
        } catch (Exception e) {
            // 文档记录尚未落库时，尝试清理已上传的对象存储文件，避免孤儿文件。
            if (document == null) {
                rustfsStorage.delete(payload.md5(), payload.originalFilename);
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

    private void markUploadFailure(DocumentDO document, Exception error) {
        if (document == null || document.getId() == null) {
            log.error("文件上传失败，未能建立文档记录", error);
            return;
        }
        // 有文档记录时统一落失败态，便于前端和运维定位失败原因。
        log.error("文件上传失败: documentId={}", document.getId(), error);
        lifecycleService.markFailed(document.getId(), error);
    }
}
