package org.buaa.rag.module.chunk;

import java.util.HashMap;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分块参数
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChunkingOptions {

    @Builder.Default
    private int chunkSize = 512;

    @Builder.Default
    private int overlapSize = 128;

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, T defaultValue) {
        if (metadata == null || !metadata.containsKey(key)) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        return value == null ? defaultValue : (T) value;
    }
}

