package org.buaa.rag.module.ingestion;

import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_PARSE_FAILED;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.STORAGE_SERVICE_ERROR;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dto.ContentFragment;
import org.buaa.rag.module.parser.DocumentParseResult;
import org.buaa.rag.module.parser.DocumentParser;
import org.buaa.rag.module.parser.DocumentParserSelector;
import org.buaa.rag.module.parser.TextCleaningService;
import org.buaa.rag.properties.FIleParseProperties;
import org.buaa.rag.service.TextChunkingService;
import org.buaa.rag.tool.RustfsStorage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档内容流水线
 * <p>
 * 负责对象存储读取、解析、清洗和分块，向上游暴露统一的内容产出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentContentPipeline {

    private final RustfsStorage rustfsStorage;
    private final TextCleaningService textCleaningService;
    private final DocumentParserSelector documentParserSelector;
    private final TextChunkingService textChunkingService;
    private final FIleParseProperties fileParseProperties;

    public List<ContentFragment> extract(DocumentDO document) {
        if (document == null) {
            return List.of();
        }
        try (InputStream inputStream = openStoredDocument(document.getMd5Hash(), document.getOriginalFileName())) {
            return extractFragments(document.getOriginalFileName(), inputStream);
        } catch (IOException e) {
            throw new ServiceException(
                "关闭对象存储文件流失败: " + e.getMessage(),
                e,
                STORAGE_SERVICE_ERROR
            );
        }
    }

    private InputStream openStoredDocument(String documentMd5, String originalFileName) {
        String primaryPath = rustfsStorage.buildPrimaryPath(documentMd5, originalFileName);
        try {
            return rustfsStorage.download(primaryPath);
        } catch (Exception e) {
            throw new ServiceException(
                "对象存储下载失败: " + e.getMessage(),
                e,
                STORAGE_SERVICE_ERROR
            );
        }
    }

    private List<ContentFragment> extractFragments(String originalFileName, InputStream inputStream) {
        String raw = extractText(inputStream, originalFileName, null);
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
        return toFragments(chunks);
    }

    private String extractText(InputStream stream, String fileName, String mimeType) {
        DocumentParser parser = documentParserSelector.selectByMimeType(mimeType, fileName);
        if (parser == null) {
            throw new ServiceException("未找到可用文档解析器", DOCUMENT_PARSE_FAILED);
        }
        try {
            DocumentParseResult result = parser.parse(stream, fileName, mimeType, buildParserOptions());
            return result == null ? "" : result.text();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("文档解析错误: " + e.getMessage(), e, DOCUMENT_PARSE_FAILED);
        }
    }

    private Map<String, Object> buildParserOptions() {
        Map<String, Object> options = new HashMap<>();
        options.put("maxExtractedChars", fileParseProperties.getMaxExtractedChars());
        return options;
    }

    private List<ContentFragment> toFragments(List<String> chunks) {
        List<ContentFragment> fragments = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            fragments.add(new ContentFragment(index + 1, chunks.get(index)));
        }
        return fragments;
    }
}
