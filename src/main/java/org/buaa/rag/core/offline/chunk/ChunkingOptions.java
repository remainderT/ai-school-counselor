package org.buaa.rag.core.offline.chunk;

import java.util.HashMap;
import java.util.Map;

/**
 * 分块参数配置。
 *
 * @param chunkSize   每块目标大小（字符数）
 * @param overlapSize 前后块重叠区域大小（字符数）
 * @param metadata    策略级附加参数（如 targetChars、maxChars 等）
 */
public record ChunkingOptions(int chunkSize, int overlapSize, Map<String, Object> metadata) {

    public ChunkingOptions {
        metadata = (metadata != null) ? metadata : new HashMap<>();
    }

    /**
     * 安全获取 metadata 中的类型化参数，不存在时返回默认值。
     */
    @SuppressWarnings("unchecked")
    public <T> T param(String key, T fallback) {
        Object val = metadata.get(key);
        return val != null ? (T) val : fallback;
    }
}
