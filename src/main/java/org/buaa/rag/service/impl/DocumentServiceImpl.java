package org.buaa.rag.service.impl;

import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_ACCESS_CONTROL_ERROR;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_NULL;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.FILE_PARSE_FAILED;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.FILE_UPLOAD_FAILED;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.STORAGE_SERVICE_ERROR;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.IndexedContentDO;
import org.buaa.rag.dao.entity.TextSegmentDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.TextSegmentMapper;
import org.buaa.rag.dto.ContentFragment;
import org.buaa.rag.service.ContentTypeDetectionService;
import org.buaa.rag.service.DocumentService;
import org.buaa.rag.service.FileValidationService;
import org.buaa.rag.service.TextCleaningService;
import org.buaa.rag.service.ingestion.DocumentIngestionStreamProducer;
import org.buaa.rag.tool.RustfsStorage;
import org.buaa.rag.tool.VectorEncoding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档服务实现层
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, DocumentDO> implements DocumentService {

    private static final String DEFAULT_VISIBILITY = "private";
    private static final String MODEL_VERSION = "text-embedding-v4";

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_PROCESSING = 1;
    private static final int STATUS_COMPLETED = 2;
    private static final int STATUS_FAILED = -1;

    private static final Set<String> PUBLIC_VISIBILITY = Set.of("public", "private");

    private final VectorEncoding encodingService;
    private final ElasticsearchClient searchClient;
    private final TextSegmentMapper segmentRepository;
    private final RustfsStorage storagePort;
    private final DocumentIngestionStreamProducer ingestionStreamProducer;
    private final FileValidationService fileValidationService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final TextCleaningService textCleaningService;

    @Value("${rustfs.bucketName}")
    private String storageBucket;

    @Value("${elasticsearch.index:knowledge_base}")
    private String indexName;

    @Value("${file.parsing.chunk-size:512}")
    private int maxChunkSize;

    @Value("${file.parsing.max-extracted-chars:800000}")
    private int maxExtractedChars;

    @Value("${file.parsing.max-cleaned-chars:500000}")
    private int maxCleanedChars;

    @Value("${rag.embedding.batch-size:10}")
    private int embeddingBatchSize;

    @Value("${rag.embedding.max-retries:2}")
    private int embeddingMaxRetries;

    @Value("${rag.embedding.retry-backoff-ms:400}")
    private long embeddingRetryBackoffMs;

    @Override
    public void upload(MultipartFile file, String visibility) {
        String originalFilename = file == null ? null : file.getOriginalFilename();
        String detectedMimeType = contentTypeDetectionService.detect(file, originalFilename);
        fileValidationService.validate(file, originalFilename, detectedMimeType);

        String md5Hash;
        try {
            md5Hash = DigestUtils.md5Hex(file.getInputStream());
        } catch (IOException ex) {
            throw new ServiceException("文件读取失败: " + ex.getMessage(), ex, FILE_UPLOAD_FAILED);
        }

        LambdaQueryWrapper<DocumentDO> queryWrapper = Wrappers.lambdaQuery(DocumentDO.class)
            .eq(DocumentDO::getMd5Hash, md5Hash)
            .eq(DocumentDO::getUserId, UserContext.getUserId());
        DocumentDO existingDoc = baseMapper.selectOne(queryWrapper);
        if (existingDoc != null) {
            throw new ClientException("文件已存在，请勿重复上传", FILE_UPLOAD_FAILED);
        }

        try {
            storeFileToRustfs(file, originalFilename, md5Hash, detectedMimeType);

            DocumentDO record = new DocumentDO();
            record.setMd5Hash(md5Hash);
            record.setOriginalFileName(originalFilename);
            record.setFileSizeBytes(file.getSize());
            record.setProcessingStatus(STATUS_PENDING);
            record.setVisibility(normalizeVisibility(visibility));
            record.setUserId(UserContext.getUserId());
            record.setProcessedAt(null);
            baseMapper.insert(record);

            ingestionStreamProducer.enqueue(md5Hash, originalFilename, 0);
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            markFailed(md5Hash, "文件上传失败", e);
            throw new ServiceException("文件上传失败: " + e.getMessage(), e, FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public List<DocumentDO> list() {
        LambdaQueryWrapper<DocumentDO> queryWrapper = Wrappers.lambdaQuery(DocumentDO.class)
            .eq(DocumentDO::getUserId, UserContext.getUserId())
            .eq(DocumentDO::getDelFlag, 0);
        return baseMapper.selectList(queryWrapper);
    }

    @Override
    public void delete(String id) {
        DocumentDO documentDO = baseMapper.selectById(id);
        if (documentDO == null || documentDO.getDelFlag() != 0) {
            throw new ClientException(DOCUMENT_NULL);
        }
        if (!documentDO.getUserId().equals(UserContext.getUserId())) {
            throw new ClientException(DOCUMENT_ACCESS_CONTROL_ERROR);
        }

        String md5Hash = documentDO.getMd5Hash();
        String objectPath = String.format("uploads/%s/%s", md5Hash, documentDO.getOriginalFileName());
        try {
            storagePort.delete(resolveBucketName(), objectPath);
        } catch (Exception e) {
            throw new ServiceException("对象存储删除失败: " + e.getMessage(), e,
                STORAGE_SERVICE_ERROR);
        }

        removeDocumentIndex(md5Hash);
        deleteSegments(md5Hash);
        baseMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void ingestDocumentTask(String documentMd5, String originalFileName) {
        String objectPath = String.format("uploads/%s/%s", documentMd5, originalFileName);
        log.info("异步摄取开始: {} -> {}", documentMd5, objectPath);

        if (!markProcessing(documentMd5)) {
            log.warn("文档记录不存在，跳过摄取: {}", documentMd5);
            return;
        }

        try (InputStream inputStream = storagePort.download(resolveBucketName(), objectPath)) {
            processAndStore(documentMd5, inputStream);
            indexDocument(documentMd5);
            markCompleted(documentMd5);
            log.info("文档摄取完成: {}", documentMd5);
        } catch (Exception e) {
            markFailed(documentMd5, "文档摄取失败", e);
            throw new ServiceException("文档摄取失败: " + e.getMessage(), e, FILE_PARSE_FAILED);
        }
    }

    private void indexDocument(String documentMd5) {
        try {
            log.info("启动文档索引流程: {}", documentMd5);

            List<ContentFragment> fragments = loadTextFragments(documentMd5);
            if (fragments == null || fragments.isEmpty()) {
                log.warn("未发现文本片段: {}", documentMd5);
                return;
            }

            List<String> textContents = extractTextContents(fragments);
            List<float[]> vectorEmbeddings = encodeInBatchesWithRetry(textContents);

            List<IndexedContentDO> indexDocuments = buildIndexedDocuments(
                documentMd5,
                fragments,
                vectorEmbeddings
            );

            performBulkIndexing(indexDocuments);

            log.info("文档索引完成: {}, 片段数: {}", documentMd5, fragments.size());
        } catch (Exception e) {
            log.error("文档索引失败: {}", documentMd5, e);
            throw new RuntimeException("向量索引过程出错", e);
        }
    }

    private void removeDocumentIndex(String documentMd5) {
        try {
            DeleteByQueryResponse response = searchClient.deleteByQuery(builder ->
                builder.index(indexName)
                    .query(query -> query.term(term -> term.field("sourceMd5").value(documentMd5)))
                    .refresh(true)
            );
            log.info("索引删除完成: {}, 删除数: {}", documentMd5, response.deleted());
        } catch (Exception e) {
            log.error("索引删除失败: {}", documentMd5, e);
        }
    }

    private void performBulkIndexing(List<IndexedContentDO> documents) {
        try {
            log.info("执行批量索引，文档数: {}", documents.size());

            List<BulkOperation> operations = documents.stream()
                .map(this::createIndexOperation)
                .collect(Collectors.toList());

            BulkRequest bulkRequest = BulkRequest.of(builder ->
                builder.operations(operations)
            );

            BulkResponse bulkResponse = searchClient.bulk(bulkRequest);

            if (bulkResponse.errors()) {
                handleBulkErrors(bulkResponse);
                throw new RuntimeException("部分文档索引失败");
            }

            log.info("批量索引成功，文档数: {}", documents.size());
        } catch (Exception e) {
            log.error("批量索引异常", e);
            throw new RuntimeException("索引操作失败", e);
        }
    }

    private BulkOperation createIndexOperation(IndexedContentDO doc) {
        return BulkOperation.of(op -> op.index(idx -> idx
            .index(indexName)
            .id(doc.getDocumentId())
            .document(doc)
        ));
    }

    private void handleBulkErrors(BulkResponse response) {
        for (BulkResponseItem item : response.items()) {
            var error = item.error();
            if (error == null) {
                continue;
            }
            log.error("索引失败 - 文档ID: {}, 原因: {}",
                item.id(), error.reason());
        }
    }

    private List<ContentFragment> loadTextFragments(String documentMd5) {
        LambdaQueryWrapper<TextSegmentDO> queryWrapper = Wrappers.lambdaQuery(TextSegmentDO.class)
            .eq(TextSegmentDO::getDocumentMd5, documentMd5)
            .orderByAsc(TextSegmentDO::getFragmentIndex);
        List<TextSegmentDO> segments = segmentRepository.selectList(queryWrapper);
        return segments.stream()
            .map(seg -> new ContentFragment(seg.getFragmentIndex(), seg.getTextData()))
            .collect(Collectors.toList());
    }

    private List<String> extractTextContents(List<ContentFragment> fragments) {
        return fragments.stream()
            .map(ContentFragment::getTextContent)
            .collect(Collectors.toList());
    }

    private List<float[]> encodeInBatchesWithRetry(List<String> textContents) {
        if (textContents == null || textContents.isEmpty()) {
            return List.of();
        }
        int batchSize = Math.max(1, embeddingBatchSize);
        List<float[]> vectors = new ArrayList<>(textContents.size());

        for (int start = 0; start < textContents.size(); start += batchSize) {
            int end = Math.min(start + batchSize, textContents.size());
            List<String> batch = textContents.subList(start, end);
            List<float[]> batchVectors = encodeBatchWithRetry(batch, start / batchSize + 1);
            vectors.addAll(batchVectors);
        }

        if (vectors.size() != textContents.size()) {
            throw new RuntimeException("向量数量与文本片段数量不一致");
        }
        return vectors;
    }

    private List<float[]> encodeBatchWithRetry(List<String> texts, int batchNo) {
        int maxAttempts = Math.max(1, embeddingMaxRetries + 1);
        RuntimeException latest = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                List<float[]> vectors = encodingService.encode(texts);
                if (vectors == null || vectors.size() != texts.size()) {
                    throw new RuntimeException("embedding 返回向量数量异常");
                }
                for (float[] vector : vectors) {
                    if (vector == null || vector.length == 0) {
                        throw new RuntimeException("embedding 返回空向量");
                    }
                }
                return vectors;
            } catch (Exception e) {
                latest = new RuntimeException("第 " + batchNo + " 批向量化失败（尝试 " + attempt + "/" + maxAttempts + "）", e);
                if (attempt < maxAttempts) {
                    sleepBeforeRetry(attempt);
                }
            }
        }
        throw latest;
    }

    private void sleepBeforeRetry(int attempt) {
        long base = Math.max(50L, embeddingRetryBackoffMs);
        long delay = base * (1L << Math.max(0, attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("向量化重试被中断", e);
        }
    }

    private List<IndexedContentDO> buildIndexedDocuments(String documentMd5,
                                                         List<ContentFragment> fragments,
                                                         List<float[]> vectors) {
        if (fragments.size() != vectors.size()) {
            throw new RuntimeException("文本片段与向量数量不匹配");
        }
        return IntStream.range(0, fragments.size())
            .mapToObj(i -> createIndexedContent(
                documentMd5,
                fragments.get(i),
                vectors.get(i)
            ))
            .collect(Collectors.toList());
    }

    private IndexedContentDO createIndexedContent(String documentMd5,
                                                  ContentFragment fragment,
                                                  float[] vector) {
        return new IndexedContentDO(
            UUID.randomUUID().toString(),
            documentMd5,
            fragment.getFragmentId(),
            fragment.getTextContent(),
            vector,
            MODEL_VERSION
        );
    }

    private void storeFileToRustfs(MultipartFile file,
                                   String filename,
                                   String fileHash,
                                   String contentType) throws Exception {
        storagePort.upload(
            resolveBucketName(),
            String.format("uploads/%s/%s", fileHash, filename),
            file.getInputStream(),
            file.getSize(),
            StringUtils.hasText(contentType) ? contentType : file.getContentType()
        );
    }

    private String resolveBucketName() {
        if (storageBucket == null || storageBucket.isBlank()) {
            return "uploads";
        }
        return storageBucket;
    }

    private String normalizeVisibility(String visibility) {
        if (!StringUtils.hasText(visibility)) {
            return DEFAULT_VISIBILITY;
        }
        String normalized = visibility.trim().toLowerCase(Locale.ROOT);
        if (PUBLIC_VISIBILITY.contains(normalized)) {
            return normalized;
        }
        return DEFAULT_VISIBILITY;
    }

    private boolean markProcessing(String documentMd5) {
        DocumentDO record = findDocumentByMd5(documentMd5);
        if (record == null) {
            return false;
        }
        record.setProcessingStatus(STATUS_PROCESSING);
        record.setProcessedAt(null);
        baseMapper.updateById(record);
        return true;
    }

    private void markCompleted(String documentMd5) {
        DocumentDO record = findDocumentByMd5(documentMd5);
        if (record == null) {
            return;
        }
        record.setProcessingStatus(STATUS_COMPLETED);
        record.setProcessedAt(LocalDateTime.now());
        baseMapper.updateById(record);
    }

    private void markFailed(String documentMd5, String message, Exception error) {
        if (StringUtils.hasText(documentMd5)) {
            log.error("{}: {}", message, documentMd5, error);
        } else {
            log.error(message, error);
            return;
        }

        DocumentDO record = findDocumentByMd5(documentMd5);
        if (record == null) {
            return;
        }
        record.setProcessingStatus(STATUS_FAILED);
        record.setProcessedAt(LocalDateTime.now());
        baseMapper.updateById(record);
    }

    private DocumentDO findDocumentByMd5(String documentMd5) {
        if (!StringUtils.hasText(documentMd5)) {
            return null;
        }
        LambdaQueryWrapper<DocumentDO> queryWrapper = Wrappers.lambdaQuery(DocumentDO.class)
            .eq(DocumentDO::getMd5Hash, documentMd5)
            .last("limit 1");
        return baseMapper.selectOne(queryWrapper);
    }

    private void processAndStore(String documentMd5, InputStream inputStream)
        throws IOException, TikaException {
        log.info("开始处理文档: {}", documentMd5);

        try {
            String extractedText = performTextExtraction(inputStream);
            String cleanedText = textCleaningService.clean(extractedText, maxCleanedChars);
            if (!StringUtils.hasText(cleanedText)) {
                throw new ServiceException("文档内容为空或不可解析", FILE_PARSE_FAILED);
            }
            log.info("文本提取成功，字符数: {}", cleanedText.length());

            List<String> textChunks = performIntelligentSegmentation(cleanedText);
            if (textChunks.isEmpty()) {
                throw new ServiceException("文档切分后为空", FILE_PARSE_FAILED);
            }
            log.info("文本分块完成，片段数: {}", textChunks.size());

            deleteSegments(documentMd5);
            persistTextSegments(documentMd5, textChunks);
            log.info("文档处理完成: {}, 总片段: {}", documentMd5, textChunks.size());

        } catch (SAXException e) {
            log.error("文档解析失败: {}", documentMd5, e);
            throw new ServiceException("文档解析错误", e, FILE_PARSE_FAILED);
        }
    }

    private void deleteSegments(String documentMd5) {
        if (documentMd5 == null || documentMd5.isBlank()) {
            return;
        }
        LambdaQueryWrapper<TextSegmentDO> queryWrapper = Wrappers.lambdaQuery(TextSegmentDO.class)
            .eq(TextSegmentDO::getDocumentMd5, documentMd5);
        segmentRepository.delete(queryWrapper);
        log.info("已删除文档分块: {}", documentMd5);
    }

    private String performTextExtraction(InputStream stream)
        throws IOException, TikaException, SAXException {
        int writeLimit = Math.max(2048, maxExtractedChars);
        BodyContentHandler contentHandler = new BodyContentHandler(writeLimit);
        Metadata documentMetadata = new Metadata();
        ParseContext parseContext = new ParseContext();
        parseContext.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());
        AutoDetectParser documentParser = new AutoDetectParser();

        try {
            documentParser.parse(stream, contentHandler, documentMetadata, parseContext);
        } catch (SAXException e) {
            if (isWriteLimitReached(e)) {
                log.warn("文档内容超过解析上限，已截断至 {} 字符", writeLimit);
            } else {
                throw e;
            }
        }

        return contentHandler.toString();
    }

    private boolean isWriteLimitReached(SAXException exception) {
        if (exception == null || exception.getMessage() == null) {
            return false;
        }
        String message = exception.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("write limit") || message.contains("more than");
    }

    private void persistTextSegments(String documentMd5, List<String> chunks) {
        int index = 1;
        for (String chunkText : chunks) {
            TextSegmentDO segment = new TextSegmentDO();
            segment.setDocumentMd5(documentMd5);
            segment.setFragmentIndex(index);
            segment.setTextData(chunkText);
            segmentRepository.insert(segment);
            index++;
        }
        log.info("已保存 {} 个文本片段", chunks.size());
    }

    private List<String> performIntelligentSegmentation(String fullText) {
        List<String> resultChunks = new ArrayList<>();
        String[] paragraphs = splitIntoParagraphs(fullText);
        StringBuilder chunkBuilder = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (paragraph.length() > maxChunkSize) {
                if (!chunkBuilder.isEmpty()) {
                    resultChunks.add(chunkBuilder.toString().trim());
                    chunkBuilder.setLength(0);
                }
                resultChunks.addAll(subdivideOverlongParagraph(paragraph));
            } else if (chunkBuilder.length() + paragraph.length() + 2 > maxChunkSize) {
                if (!chunkBuilder.isEmpty()) {
                    resultChunks.add(chunkBuilder.toString().trim());
                }
                chunkBuilder = new StringBuilder(paragraph);
            } else {
                if (!chunkBuilder.isEmpty()) {
                    chunkBuilder.append("\n\n");
                }
                chunkBuilder.append(paragraph);
            }
        }

        if (!chunkBuilder.isEmpty()) {
            resultChunks.add(chunkBuilder.toString().trim());
        }

        return resultChunks;
    }

    private String[] splitIntoParagraphs(String text) {
        return text.split("\\n\\n+");
    }

    private List<String> subdivideOverlongParagraph(String paragraph) {
        List<String> subChunks = new ArrayList<>();
        String[] sentences = splitIntoSentences(paragraph);
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (sentence.length() > maxChunkSize) {
                if (currentChunk.length() > 0) {
                    subChunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }
                subChunks.addAll(subdivideOverlongSentence(sentence));
            } else if (currentChunk.length() + sentence.length() > maxChunkSize) {
                if (currentChunk.length() > 0) {
                    subChunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }
                currentChunk.append(sentence);
            } else {
                currentChunk.append(sentence);
            }
        }

        if (currentChunk.length() > 0) {
            subChunks.add(currentChunk.toString().trim());
        }

        return subChunks;
    }

    private String[] splitIntoSentences(String paragraph) {
        return paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");
    }

    private List<String> subdivideOverlongSentence(String sentence) {
        try {
            List<String> wordChunks = new ArrayList<>();
            List<Term> terms = StandardTokenizer.segment(sentence);
            StringBuilder wordBuilder = new StringBuilder();

            for (Term term : terms) {
                String word = term.word;

                if (wordBuilder.length() + word.length() > maxChunkSize && !wordBuilder.isEmpty()) {
                    wordChunks.add(wordBuilder.toString());
                    wordBuilder.setLength(0);
                }
                wordBuilder.append(word);
            }

            if (wordBuilder.length() > 0) {
                wordChunks.add(wordBuilder.toString());
            }

            log.debug("HanLP分词 - 原句: {} 字符, 分词: {} 个, 片段: {} 个",
                sentence.length(), terms.size(), wordChunks.size());
            return wordChunks;

        } catch (Exception e) {
            log.warn("HanLP分词失败，回退到字符分割: {}", e.getMessage());
            return fallbackCharacterSplit(sentence);
        }
    }

    private List<String> fallbackCharacterSplit(String text) {
        List<String> chunks = new ArrayList<>();

        int position = 0;
        while (position < text.length()) {
            int endPosition = Math.min(position + maxChunkSize, text.length());
            chunks.add(text.substring(position, endPosition));
            position = endPosition;
        }

        return chunks;
    }

    private static class NoOpEmbeddedDocumentExtractor implements EmbeddedDocumentExtractor {

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return false;
        }

        @Override
        public void parseEmbedded(InputStream stream,
                                  ContentHandler handler,
                                  Metadata metadata,
                                  boolean outputHtml) throws SAXException, IOException {
            // no-op: 禁止递归解析嵌入对象，避免异常文件导致解析放大
        }
    }
}
