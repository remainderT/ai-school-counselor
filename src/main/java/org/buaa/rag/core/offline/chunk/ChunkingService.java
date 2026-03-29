package org.buaa.rag.core.offline.chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.properties.FIleParseProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文本智能分块服务
 * <p>
 * 将长文本按段落 → 句子 → 分词逐级拆分为不超过上限的文本片段，
 * 保持语义完整性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkingService {

    private static final int MIN_CHUNK_SIZE = 1;
    private static final int MIN_SEMANTIC_CHARS = 64;
    private static final int DEFAULT_TARGET_CHARS = 1400;
    private static final int DEFAULT_MAX_CHARS = 1800;
    private static final int DEFAULT_MIN_CHARS = 600;

    private final FIleParseProperties fileParseProperties;

    // 直接组合两种具体策略，去掉 Strategy 接口和 Factory 的额外层级。
    private final FixedSizeChunkingStrategy fixedSizeChunkingStrategy = new FixedSizeChunkingStrategy();
    private final StructureAwareChunkingStrategy structureAwareChunkingStrategy = new StructureAwareChunkingStrategy();

    /**
     * 将文本分割为多个片段
     */
    public List<String> chunk(String fullText, int maxChunkSize) {
        return chunk(fullText, maxChunkSize, null);
    }

    public List<String> chunk(String fullText, int maxChunkSize, String overrideChunkMode) {
        if (!StringUtils.hasText(fullText)) {
            return List.of();
        }

        // 上传入参可覆盖全局默认策略，未指定时仍走配置值。
        String modeValue = StringUtils.hasText(overrideChunkMode) ? overrideChunkMode : fileParseProperties.getChunkMode();
        ChunkingMode mode = ChunkingMode.fromValue(modeValue);
        int configuredChunkSize = Math.max(MIN_CHUNK_SIZE, fileParseProperties.getChunkSize());
        // 运行时传入值优先于配置值，便于不同离线任务按需调节 chunk 大小。
        int chunkSize = maxChunkSize > 0 ? Math.max(MIN_CHUNK_SIZE, maxChunkSize) : configuredChunkSize;
        ChunkingOptions options = buildOptions(chunkSize);

        List<String> rawChunks = switch (mode) {
            case FIXED_SIZE -> fixedSizeChunkingStrategy.chunk(fullText, options);
            case STRUCTURE_AWARE -> structureAwareChunkingStrategy.chunk(fullText, options);
        };
        if (rawChunks.isEmpty() && mode != ChunkingMode.FIXED_SIZE) {
            // 结构化分块失败时兜底固定窗口，保证离线链路可继续推进。
            log.warn("分块模式 {} 结果为空，回退 fixed_size", mode);
            rawChunks = fixedSizeChunkingStrategy.chunk(fullText, options);
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

        return new ChunkingOptions(chunkSize, overlap, metadata);
    }

    private int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private List<String> compact(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        // 统一去空白，避免后续 embedding/索引阶段处理无效 chunk。
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
