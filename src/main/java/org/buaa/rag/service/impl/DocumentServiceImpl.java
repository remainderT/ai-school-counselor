package org.buaa.rag.service.impl;

import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.DOCUMENT_NOT_FOUND;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.FILE_ACCESS_DENIED;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.FILE_TYPE_NOT_SUPPORTED;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.FILE_UPLOAD_FAILED;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.STORAGE_SERVICE_ERROR;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


import org.apache.commons.codec.digest.DigestUtils;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.IndexedContentDO;
import org.buaa.rag.dao.entity.TextSegmentDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.TextSegmentMapper;
import org.buaa.rag.dto.ContentFragment;
import org.buaa.rag.service.DocumentService;
import org.buaa.rag.tool.VectorEncoding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
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
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档服务实现层
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, DocumentDO> implements DocumentService {

    private static final String DEFAULT_VISIBILITY = "PRIVATE";
    private static final String MODEL_VERSION = "text-embedding-v4";
    private static final Set<String> ALLOWED_FILE_TYPES = Set.of(
            "pdf", "doc", "docx", "txt", "md", "html", "htm",
            "xls", "xlsx", "ppt", "pptx", "rtf", "csv"
    );

    private final MinioClient minioClient;
    private final VectorEncoding encodingService;
    private final ElasticsearchClient searchClient;
    private final TextSegmentMapper segmentRepository;

    @Lazy
    @Autowired
    private DocumentServiceImpl self;

    @Value("${minio.bucketName}")
    private String storageBucket;

    @Value("${elasticsearch.index:knowledge_base}")
    private String indexName;

    @Value("${file.parsing.chunk-size}")
    private int maxChunkSize;

    @Override
    @Transactional
    public Result<Map<String, Object>> upload(MultipartFile file, String visibility, String department, String docType, String policyYear, String tags) {
        String originalFilename = file.getOriginalFilename();
        if (!isValidFileType(originalFilename)) {
            throw new ClientException(FILE_TYPE_NOT_SUPPORTED);
        }

        String md5Hash;
        try {
            md5Hash = DigestUtils.md5Hex(file.getInputStream());
        } catch (IOException ex) {
            throw new ServiceException("文件读取失败: " + ex.getMessage(), ex, FILE_UPLOAD_FAILED);
        }

        // 检查是否已存在相同文件
        String currentUserId = String.valueOf(UserContext.getUserId());
        LambdaQueryWrapper<DocumentDO> queryWrapper = Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getMd5Hash, md5Hash)
                .eq(DocumentDO::getUserId, currentUserId);
        DocumentDO existingDoc = baseMapper.selectOne(queryWrapper);
        if (existingDoc != null) {
            throw new ClientException("文件已存在，请勿重复上传", FILE_UPLOAD_FAILED);
        }

        try {
            // 存储文件到 MinIO
            storeFileToMinio(file, originalFilename, md5Hash);

            // 创建文档记录
            DocumentDO record = new DocumentDO();
            record.setMd5Hash(md5Hash);
            record.setOriginalFileName(originalFilename);
            record.setFileSizeBytes(file.getSize());
            record.setProcessingStatus(0);
            record.setVisibility(visibility != null ? visibility : DEFAULT_VISIBILITY);
            record.setUserId(currentUserId);
            record.setProcessedAt(null);
            baseMapper.insert(record);

            // 使用代理触发 @Async
            self.ingestDocumentAsync(md5Hash, originalFilename);

            return Results.success(Map.of("md5Hash", md5Hash, "message", "上传成功"));

        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("文件上传失败: " + e.getMessage(), e, FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public Result<List<DocumentDO>> listDocuments(String userId) {
        List<DocumentDO> documentDOS = baseMapper.findByOwnerIdOrderByUploadedAtDesc(userId);
        return Results.success(documentDOS);
    }

    @Override
    @Transactional
    public Result<Map<String, Object>> deleteDocument(String md5Hash, String userId) {
        Optional<DocumentDO> recordOpt = baseMapper.findByMd5Hash(md5Hash);
        if (recordOpt.isEmpty()) {
            throw new ClientException("文档不存在", DOCUMENT_NOT_FOUND);
        }

        DocumentDO record = recordOpt.get();
        if (!Objects.equals(record.getUserId(), userId)) {
            throw new ClientException("无权限删除该文档", FILE_ACCESS_DENIED);
        }

        String objectPath = String.format("uploads/%s/%s", md5Hash, record.getOriginalFileName());
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(resolveBucketName())
                .object(objectPath)
                .build());
        } catch (Exception e) {
            throw new ServiceException("对象存储删除失败: " + e.getMessage(), e,
                STORAGE_SERVICE_ERROR);
        }

        removeDocumentIndex(md5Hash);
        deleteSegments(md5Hash);
        baseMapper.deleteByMd5Hash(md5Hash);

        return Results.success(Map.of("fileMd5", md5Hash, "message", "删除成功"));
    }

    @Override
    @Async("documentIngestionExecutor")
    @Transactional
    public void ingestDocumentAsync(String documentMd5, String originalFileName) {
        String objectPath = String.format("uploads/%s/%s", documentMd5, originalFileName);
        log.info("异步摄取开始: {} -> {}", documentMd5, objectPath);

        if (!markProcessing(documentMd5)) {
            log.warn("文档记录不存在，跳过摄取: {}", documentMd5);
            return;
        }

        try (InputStream inputStream = minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(resolveBucketName())
                .object(objectPath)
                .build()
        )) {
            processAndStore(documentMd5, inputStream);
        } catch (Exception e) {
            markFailed(documentMd5, "文档解析失败", e);
            return;
        }

        try {
            indexDocument(documentMd5);
            markCompleted(documentMd5);
            log.info("文档摄取完成: {}", documentMd5);
        } catch (Exception e) {
            markFailed(documentMd5, "向量索引失败", e);
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
            List<float[]> vectorEmbeddings = encodingService.encode(textContents);

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
        List<TextSegmentDO> segments = segmentRepository.findByDocumentMd5(documentMd5);
        return segments.stream()
            .map(seg -> new ContentFragment(seg.getFragmentIndex(), seg.getTextData()))
            .collect(Collectors.toList());
    }

    private List<String> extractTextContents(List<ContentFragment> fragments) {
        return fragments.stream()
            .map(ContentFragment::getTextContent)
            .collect(Collectors.toList());
    }

    private List<IndexedContentDO> buildIndexedDocuments(String documentMd5,
                                                         List<ContentFragment> fragments,
                                                         List<float[]> vectors) {
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

    private boolean isValidFileType(String filename) {
        if (filename == null) {
            return false;
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex == -1) {
            return false;
        }
        String extension = filename.substring(dotIndex + 1).toLowerCase();
        return ALLOWED_FILE_TYPES.contains(extension);
    }

    private void storeFileToMinio(MultipartFile file, String filename, String fileHash) throws Exception {
        minioClient.putObject(PutObjectArgs.builder()
            .bucket(resolveBucketName())
            .object(String.format("uploads/%s/%s", fileHash, filename))
            .stream(file.getInputStream(), file.getSize(), -1)
            .contentType(file.getContentType())
            .build());
    }

    private String normalizeTags(String tags) {
        List<String> tagList = parseTags(tags);
        if (tagList.isEmpty()) {
            return null;
        }
        return String.join(",", tagList);
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = tags.split("[,，;；]");
        List<String> results = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                results.add(trimmed);
            }
        }
        return results.stream().distinct().toList();
    }

    private String resolveBucketName() {
        if (storageBucket == null || storageBucket.isBlank()) {
            return "uploads";
        }
        return storageBucket;
    }

    private boolean markProcessing(String documentMd5) {
        Optional<DocumentDO> recordOpt = baseMapper.findByMd5Hash(documentMd5);
        if (recordOpt.isEmpty()) {
            return false;
        }
        DocumentDO record = recordOpt.get();
        record.setProcessingStatus(0);
        record.setProcessedAt(null);
        baseMapper.updateById(record);
        return true;
    }

    private void markCompleted(String documentMd5) {
        baseMapper.findByMd5Hash(documentMd5).ifPresent(record -> {
            record.setProcessingStatus(1);
            record.setProcessedAt(LocalDateTime.now());
            baseMapper.updateById(record);
        });
    }

    private void markFailed(String documentMd5, String message, Exception error) {
        log.error("{}: {}", message, documentMd5, error);
        baseMapper.findByMd5Hash(documentMd5).ifPresent(record -> {
            record.setProcessingStatus(-1);
            record.setProcessedAt(LocalDateTime.now());
            baseMapper.updateById(record);
        });
    }

    private void processAndStore(String documentMd5, InputStream inputStream)
            throws IOException, TikaException {
        log.info("开始处理文档: {}", documentMd5);

        try {
            String extractedText = performTextExtraction(inputStream);
            log.info("文本提取成功，字符数: {}", extractedText.length());

            List<String> textChunks = performIntelligentSegmentation(extractedText);
            log.info("文本分块完成，片段数: {}", textChunks.size());

            persistTextSegments(documentMd5, textChunks);
            log.info("文档处理完成: {}, 总片段: {}", documentMd5, textChunks.size());

        } catch (SAXException e) {
            log.error("文档解析失败: {}", documentMd5, e);
            throw new RuntimeException("文档解析错误", e);
        }
    }

    private void deleteSegments(String documentMd5) {
        if (documentMd5 == null || documentMd5.isBlank()) {
            return;
        }
        segmentRepository.deleteByDocumentMd5(documentMd5);
        log.info("已删除文档分块: {}", documentMd5);
    }

    private String performTextExtraction(InputStream stream)
            throws IOException, TikaException, SAXException {
        BodyContentHandler contentHandler = new BodyContentHandler(-1);
        Metadata documentMetadata = new Metadata();
        ParseContext parseContext = new ParseContext();
        AutoDetectParser documentParser = new AutoDetectParser();

        documentParser.parse(stream, contentHandler, documentMetadata, parseContext);
        return contentHandler.toString();
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
        return text.split("\n\n+");
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

                if (wordBuilder.length() + word.length() > maxChunkSize && wordBuilder.length() > 0) {
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
}
