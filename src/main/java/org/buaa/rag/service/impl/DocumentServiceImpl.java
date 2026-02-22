package org.buaa.rag.service.impl;

import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_ACCESS_CONTROL_ERROR;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_EXISTS;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_MIME_FAILED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_NOT_EXISTS;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_PARSE_FAILED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_SIZE_EXCEEDED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_TYPE_NOT_SUPPORTED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_UPLOAD_FAILED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_UPLOAD_NULL;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.STORAGE_SERVICE_ERROR;
import static org.buaa.rag.common.enums.UploadStatusEnum.COMPLETED;
import static org.buaa.rag.common.enums.UploadStatusEnum.FAILED_FINAL;
import static org.buaa.rag.common.enums.UploadStatusEnum.FAILED_RETRYABLE;
import static org.buaa.rag.common.enums.UploadStatusEnum.PENDING;
import static org.buaa.rag.common.enums.UploadStatusEnum.PROCESSING;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.DocumentIngestionException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.TextSegmentDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.TextSegmentMapper;
import org.buaa.rag.dto.ContentFragment;
import org.buaa.rag.mq.DocumentIngestionProducer;
import org.buaa.rag.properties.FIleParseProperties;
import org.buaa.rag.service.DocumentIndexingService;
import org.buaa.rag.service.DocumentService;
import org.buaa.rag.service.TextChunkingService;
import org.buaa.rag.service.TextCleaningService;
import org.buaa.rag.tool.FileTypeValidate;
import org.buaa.rag.tool.RustfsStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * 文档服务实现层
 * <p>
 * 负责文档的上传、解析、分块持久化以及生命周期管理。
 * 向量索引委托给 {@link DocumentIndexingService}，
 * 文本分块委托给 {@link TextChunkingService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, DocumentDO> implements DocumentService {

    private static final String DEFAULT_VISIBILITY = "private";
    private static final int MAGIC_HEADER_BYTES = 16;
    private static final int MAX_FAILURE_REASON_LENGTH = 512;

    private final RustfsStorage rustfsStorage;
    private final DocumentIngestionProducer ingestionProducer;
    private final TextCleaningService textCleaningService;
    private final TextChunkingService textChunkingService;
    private final DocumentIndexingService indexingService;
    private final TextSegmentMapper segmentMapper;
    private final FIleParseProperties fileParseProperties;


    private final Tika tika = new Tika();

    @Override
    public void upload(MultipartFile file, String visibility) {
        if (file == null || file.isEmpty()) {
            throw new ClientException(DOCUMENT_UPLOAD_NULL);
        }
        if (file.getSize() > fileParseProperties.getMaxUploadBytes()) {
            throw new ClientException(DOCUMENT_SIZE_EXCEEDED);
        }

        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            throw new ClientException("文件名不能为空", DOCUMENT_TYPE_NOT_SUPPORTED);
        }
        byte[] fileHeader = readFileHeader(file);
        String mimeType;
        try (InputStream in = file.getInputStream()) {
            mimeType = tika.detect(in, originalFilename);
        } catch (IOException e) {
            log.warn("MIME 检测失败: {}", e.getMessage());
            throw new ServiceException(DOCUMENT_MIME_FAILED);
        }
        FileTypeValidate.validate(originalFilename, mimeType, fileHeader);

        String md5;
        try {
            md5 = DigestUtils.md5Hex(file.getInputStream());
        } catch (IOException e) {
            throw new ServiceException("文档解析失败: " + e.getMessage(), e, DOCUMENT_PARSE_FAILED);
        }

        DocumentDO existing = baseMapper.selectOne(
                Wrappers.lambdaQuery(DocumentDO.class)
                        .eq(DocumentDO::getMd5Hash, md5)
                        .eq(DocumentDO::getUserId, UserContext.getUserId())
        );
        if (existing != null) {
            throw new ClientException(DOCUMENT_EXISTS);
        }

        try {
            rustfsStorage.upload(buildObjectPath(md5, originalFilename), file.getInputStream(), file.getSize(),
                    StringUtils.hasText(mimeType) ? mimeType : file.getContentType()
            );

            insertDocumentRecord(md5, originalFilename, file.getSize(), visibility);
            ingestionProducer.enqueue(md5, originalFilename, 0);
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            markFailed(md5, "文件上传失败", e);
            throw new ServiceException("文件上传失败: " + e.getMessage(), e, DOCUMENT_UPLOAD_FAILED);
        }
    }

    @Override
    public List<DocumentDO> list() {
        return baseMapper.selectList(
            Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getUserId, UserContext.getUserId())
                .eq(DocumentDO::getDelFlag, 0)
        );
    }

    @Override
    public void delete(String id) {
        DocumentDO doc = baseMapper.selectById(id);
        if (doc == null || doc.getDelFlag() != 0) {
            throw new ClientException(DOCUMENT_NOT_EXISTS);
        }
        if (!doc.getUserId().equals(UserContext.getUserId())) {
            throw new ClientException(DOCUMENT_ACCESS_CONTROL_ERROR);
        }

        String md5 = doc.getMd5Hash();
        deleteFromStorage(md5, doc.getOriginalFileName());
        indexingService.removeIndex(md5);
        deleteSegments(md5);
        baseMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void ingestDocumentTask(String documentMd5, String originalFileName) {
        String objectPath = buildObjectPath(documentMd5, originalFileName);
        log.info("异步摄取开始: {} -> {}", documentMd5, objectPath);

        if (!markProcessing(documentMd5)) {
            log.warn("文档记录不存在，跳过摄取: {}", documentMd5);
            return;
        }

        try (InputStream in = downloadForIngestion(documentMd5, originalFileName)) {
            List<ContentFragment> fragments = extractAndChunk(documentMd5, in);
            indexingService.index(documentMd5, fragments);
            markCompleted(documentMd5);
            log.info("文档摄取完成: {}", documentMd5);
        } catch (Exception e) {
            boolean retryable = isRetryableIngestionFailure(e);
            if (retryable) {
                markRetryableFailure(documentMd5, "文档摄取失败", e);
            } else {
                markFinalFailure(documentMd5, "文档摄取失败", e);
            }
            throw new DocumentIngestionException("文档摄取失败: " + e.getMessage(), e, retryable);
        }
    }

    @Override
    public void markIngestionFinalFailure(String documentMd5, String failureReason) {
        updateStatus(documentMd5, FAILED_FINAL.getCode(), LocalDateTime.now(), failureReason);
    }

    // ═══════════════════ 上传辅助 ═══════════════════


    private void insertDocumentRecord(String md5, String filename, long size, String visibility) {
        DocumentDO record = new DocumentDO();
        record.setMd5Hash(md5);
        record.setOriginalFileName(filename);
        record.setFileSizeBytes(size);
        record.setProcessingStatus(PENDING.getCode());
        record.setVisibility(visibility == null ? DEFAULT_VISIBILITY : visibility);
        record.setUserId(UserContext.getUserId());
        record.setFailureReason(null);
        baseMapper.insert(record);
    }

    // ═══════════════════ 文档解析与分块 ═══════════════════

    /**
     * 提取文本 → 清洗 → 分块 → 持久化，返回片段列表供后续索引使用
     */
    private List<ContentFragment> extractAndChunk(String documentMd5, InputStream in)
        throws IOException, TikaException {
        try {
            String raw = extractText(in);
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

            deleteSegments(documentMd5);
            persistSegments(documentMd5, chunks);

            return toFragments(chunks);
        } catch (SAXException e) {
            throw new ServiceException("文档解析错误", e, DOCUMENT_PARSE_FAILED);
        }
    }

    private String extractText(InputStream stream) throws IOException, TikaException, SAXException {
        int writeLimit = Math.max(2048, fileParseProperties.getMaxExtractedChars());
        BodyContentHandler handler = new BodyContentHandler(writeLimit);
        Metadata metadata = new Metadata();
        AutoDetectParser parser = new AutoDetectParser();
        ParseContext ctx = new ParseContext();
        ctx.set(Parser.class, parser);
        ctx.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedExtractor());
        ctx.set(PDFParserConfig.class, buildPdfParserConfig());

        try {
            parser.parse(stream, handler, metadata, ctx);
        } catch (SAXException e) {
            if (isWriteLimitReached(e)) {
                log.warn("文档内容超过解析上限，已截断至 {} 字符", writeLimit);
            } else {
                throw e;
            }
        }
        return handler.toString();
    }

    private void persistSegments(String documentMd5, List<String> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            TextSegmentDO seg = new TextSegmentDO();
            seg.setDocumentMd5(documentMd5);
            seg.setFragmentIndex(i + 1);
            seg.setTextData(chunks.get(i));
            segmentMapper.insert(seg);
        }
        log.info("已保存 {} 个文本片段", chunks.size());
    }

    private List<ContentFragment> toFragments(List<String> chunks) {
        List<ContentFragment> fragments = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            fragments.add(new ContentFragment(i + 1, chunks.get(i)));
        }
        return fragments;
    }

    // ═══════════════════ 存储操作 ═══════════════════

    private void deleteFromStorage(String md5, String filename) {
        String primaryPath = buildObjectPath(md5, filename);
        String legacyPath = buildLegacyObjectPath(md5, filename);
        try {
            rustfsStorage.delete(primaryPath);
            if (StringUtils.hasText(legacyPath) && !primaryPath.equals(legacyPath)) {
                // 兼容历史对象路径，双删避免遗留旧路径文件
                rustfsStorage.delete(legacyPath);
            }
        } catch (Exception e) {
            throw new ServiceException("对象存储删除失败: " + e.getMessage(), e, STORAGE_SERVICE_ERROR);
        }
    }

    private void deleteSegments(String documentMd5) {
        if (!StringUtils.hasText(documentMd5)) {
            return;
        }
        segmentMapper.delete(
            Wrappers.lambdaQuery(TextSegmentDO.class)
                .eq(TextSegmentDO::getDocumentMd5, documentMd5)
        );
        log.debug("已删除文档分块: {}", documentMd5);
    }

    // ═══════════════════ 状态管理 ═══════════════════

    private boolean markProcessing(String md5) {
        return updateStatus(md5, PROCESSING.getCode(), null, null);
    }

    private void markCompleted(String md5) {
        updateStatus(md5, COMPLETED.getCode(), LocalDateTime.now(), null);
    }

    private void markRetryableFailure(String md5, String message, Exception error) {
        if (!StringUtils.hasText(md5)) {
            log.warn(message, error);
            return;
        }
        log.warn("{}: {}", message, md5, error);
        updateStatus(md5, FAILED_RETRYABLE.getCode(), null, summarizeFailureReason(error));
    }

    private void markFinalFailure(String md5, String message, Exception error) {
        if (!StringUtils.hasText(md5)) {
            log.error(message, error);
            return;
        }
        log.error("{}: {}", message, md5, error);
        updateStatus(md5, FAILED_FINAL.getCode(), LocalDateTime.now(), summarizeFailureReason(error));
    }

    private void markFailed(String md5, String message, Exception error) {
        markFinalFailure(md5, message, error);
    }

    private boolean updateStatus(String md5, Integer status, LocalDateTime processedAt, String failureReason) {
        DocumentDO record = findByMd5(md5);
        if (record == null) {
            return false;
        }
        record.setProcessingStatus(status);
        record.setProcessedAt(processedAt);
        record.setFailureReason(normalizeFailureReason(failureReason));
        baseMapper.updateById(record);
        return true;
    }

    // ═══════════════════ 通用工具 ═══════════════════

    private DocumentDO findByMd5(String md5) {
        if (!StringUtils.hasText(md5)) {
            return null;
        }
        return baseMapper.selectOne(
            Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getMd5Hash, md5)
                .last("limit 1")
        );
    }

    private String buildObjectPath(String md5, String filename) {
        String extension = extractExtension(filename);
        String suffix = StringUtils.hasText(extension) ? "." + extension : "";
        // 使用服务端命名，避免原始文件名带来的路径与特殊字符风险
        return String.format("uploads/%s/source%s", md5, suffix);
    }

    private boolean isWriteLimitReached(SAXException e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String msg = e.getMessage().toLowerCase(Locale.ROOT);
        return msg.contains("write limit") || msg.contains("more than");
    }

    private byte[] readFileHeader(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return inputStream.readNBytes(MAGIC_HEADER_BYTES);
        } catch (IOException e) {
            throw new ServiceException("文件头读取失败: " + e.getMessage(), e, DOCUMENT_PARSE_FAILED);
        }
    }

    private PDFParserConfig buildPdfParserConfig() {
        PDFParserConfig config = new PDFParserConfig();
        config.setExtractInlineImages(false);
        config.setSortByPosition(true);
        config.setOcrStrategy(fileParseProperties.isEnableOcr()
                ? PDFParserConfig.OCR_STRATEGY.AUTO
                : PDFParserConfig.OCR_STRATEGY.NO_OCR);
        return config;
    }

    private InputStream downloadForIngestion(String md5, String filename) throws Exception {
        String primaryPath = buildObjectPath(md5, filename);
        try {
            return rustfsStorage.download(primaryPath);
        } catch (Exception primaryError) {
            String legacyPath = buildLegacyObjectPath(md5, filename);
            if (!StringUtils.hasText(legacyPath) || primaryPath.equals(legacyPath) || !isNoSuchKey(primaryError)) {
                throw primaryError;
            }
            log.warn("主路径下载失败，回退 legacy 路径下载: md5={}, legacyPath={}", md5, legacyPath);
            return rustfsStorage.download(legacyPath);
        }
    }

    private boolean isRetryableIngestionFailure(Throwable throwable) {
        if (containsCause(throwable, ClientException.class)) {
            return false;
        }
        if (containsCause(throwable, TikaException.class) || containsCause(throwable, SAXException.class)) {
            return false;
        }
        ServiceException serviceException = findCause(throwable, ServiceException.class);
        if (serviceException != null && DOCUMENT_PARSE_FAILED.code().equals(serviceException.getErrorCode())) {
            return false;
        }
        Throwable root = rootCause(throwable);
        if (root instanceof NoSuchKeyException || root instanceof FileNotFoundException) {
            return false;
        }
        if (root instanceof NullPointerException && isFromTika(root)) {
            return false;
        }
        if (root instanceof IOException) {
            return isTransientIo((IOException) root);
        }
        return root instanceof SdkException;
    }

    private boolean isTransientIo(IOException ioException) {
        String msg = ioException.getMessage();
        if (!StringUtils.hasText(msg)) {
            return true;
        }
        String normalized = msg.toLowerCase(Locale.ROOT);
        return normalized.contains("timeout")
                || normalized.contains("timed out")
                || normalized.contains("connection reset")
                || normalized.contains("broken pipe")
                || normalized.contains("temporarily unavailable");
    }

    private boolean isFromTika(Throwable throwable) {
        for (StackTraceElement element : throwable.getStackTrace()) {
            if (element.getClassName().startsWith("org.apache.tika.")) {
                return true;
            }
        }
        return false;
    }

    private String summarizeFailureReason(Throwable throwable) {
        Throwable root = rootCause(throwable);
        String message = root.getMessage();
        String prefix = root.getClass().getSimpleName();
        String summary = StringUtils.hasText(message) ? prefix + ": " + message : prefix;
        return normalizeFailureReason(summary);
    }

    private String normalizeFailureReason(String failureReason) {
        if (!StringUtils.hasText(failureReason)) {
            return null;
        }
        String value = failureReason.trim();
        if (value.length() <= MAX_FAILURE_REASON_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    private String extractExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String buildLegacyObjectPath(String md5, String filename) {
        if (!StringUtils.hasText(filename)) {
            return null;
        }
        return String.format("uploads/%s/%s", md5, filename);
    }

    private boolean isNoSuchKey(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof NoSuchKeyException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private <T extends Throwable> boolean containsCause(Throwable throwable, Class<T> type) {
        return findCause(throwable, type) != null;
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    // ═══════════════════ 内部类 ═══════════════════

    /**
     * 禁止递归解析嵌入对象，避免异常文件导致解析放大
     */
    private static class NoOpEmbeddedExtractor implements EmbeddedDocumentExtractor {
        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return false;
        }

        @Override
        public void parseEmbedded(InputStream stream, ContentHandler handler,
                                  Metadata metadata, boolean outputHtml) {
            // no-op
        }
    }
}
