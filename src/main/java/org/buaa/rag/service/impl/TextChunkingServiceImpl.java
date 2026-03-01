package org.buaa.rag.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.core.chunk.ChunkingMode;
import org.buaa.rag.core.chunk.ChunkingOptions;
import org.buaa.rag.core.chunk.ChunkingStrategy;
import org.buaa.rag.core.chunk.ChunkingStrategyFactory;
import org.buaa.rag.properties.FIleParseProperties;
import org.buaa.rag.service.TextChunkingService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 分块服务实现：支持固定窗口、结构感知、句子、段落多策略
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextChunkingServiceImpl implements TextChunkingService {

    private static final int MIN_CHUNK_SIZE = 1;
    private static final int MIN_SEMANTIC_CHARS = 64;
    private static final int DEFAULT_TARGET_CHARS = 1400;
    private static final int DEFAULT_MAX_CHARS = 1800;
    private static final int DEFAULT_MIN_CHARS = 600;

    private final FIleParseProperties fileParseProperties;
    private final ChunkingStrategyFactory chunkingStrategyFactory;

    @Override
    public List<String> chunk(String fullText, int maxChunkSize) {
        if (!StringUtils.hasText(fullText)) {
            return List.of();
        }

        ChunkingMode mode = ChunkingMode.fromValue(fileParseProperties.getChunkMode());
        int configuredChunkSize = Math.max(MIN_CHUNK_SIZE, fileParseProperties.getChunkSize());
        int chunkSize = maxChunkSize > 0 ? Math.max(MIN_CHUNK_SIZE, maxChunkSize) : configuredChunkSize;
        ChunkingOptions options = buildOptions(chunkSize);

        ChunkingStrategy strategy = chunkingStrategyFactory.findStrategy(mode)
            .orElseGet(() -> {
                log.warn("未找到分块策略 {}, 回退 structure_aware", mode);
                return chunkingStrategyFactory.requireStrategy(ChunkingMode.STRUCTURE_AWARE);
            });
        List<String> rawChunks = strategy.chunk(fullText, options);
        if (rawChunks.isEmpty() && mode != ChunkingMode.FIXED_SIZE) {
            log.warn("分块策略 {} 结果为空，回退 fixed_size", mode);
            rawChunks = chunkingStrategyFactory.requireStrategy(ChunkingMode.FIXED_SIZE).chunk(fullText, options);
        }
        return compact(rawChunks);
    }

    private ChunkingOptions buildOptions(int chunkSize) {
        int overlap = Math.max(0, Math.min(fileParseProperties.getOverlapSize(), chunkSize - 1));
        int target = Math.max(chunkSize, positiveOrDefault(fileParseProperties.getSemanticTargetChars(), DEFAULT_TARGET_CHARS));
        int max = Math.max(target, positiveOrDefault(fileParseProperties.getSemanticMaxChars(), DEFAULT_MAX_CHARS));
        int min = Math.max(MIN_SEMANTIC_CHARS, Math.min(target, positiveOrDefault(fileParseProperties.getSemanticMinChars(), DEFAULT_MIN_CHARS)));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("targetChars", target);
        metadata.put("maxChars", max);
        metadata.put("minChars", min);
        metadata.put("overlapChars", Math.max(0, fileParseProperties.getSemanticOverlapChars()));
        return ChunkingOptions.builder()
            .chunkSize(chunkSize)
            .overlapSize(overlap)
            .metadata(metadata)
            .build();
    }

    private int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private List<String> compact(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            if (!StringUtils.hasText(chunk)) {
                continue;
            }
            String normalized = chunk.trim();
            if (!normalized.isEmpty()) {
                sanitized.add(normalized);
            }
        }
        return sanitized;
    }
}
