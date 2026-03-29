package org.buaa.rag.core.offline.chunk;

import java.util.HashMap;
import java.util.Map;

/**
 * 分块参数
 */
public record ChunkingOptions(int chunkSize, int overlapSize, Map<String, Object> metadata) {

    public ChunkingOptions {
        metadata = metadata == null ? new HashMap<>() : metadata;
    }

    public <T> T getMetadata(String key, T defaultValue) {
        if (!metadata.containsKey(key)) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        return value == null ? defaultValue : (T) value;
    }
}
