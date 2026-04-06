package org.buaa.rag.core.offline.chunk;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文档分块策略类型。
 */
@Getter
@AllArgsConstructor
public enum ChunkingMode {

    FIXED_SIZE("fixed_size"),
    STRUCTURE_AWARE("structure_aware");

    private final String value;

    /**
     * 将用户输入（可能是连字符或下划线格式）解析为枚举值，
     * 无法识别时默认回退到 {@link #STRUCTURE_AWARE}。
     */
    public static ChunkingMode resolve(String input) {
        if (input == null || input.isBlank()) {
            return STRUCTURE_AWARE;
        }
        String cleaned = input.strip().toLowerCase().replace('-', '_');
        for (ChunkingMode mode : values()) {
            if (mode.value.equalsIgnoreCase(cleaned) || mode.name().equalsIgnoreCase(cleaned)) {
                return mode;
            }
        }
        return STRUCTURE_AWARE;
    }
}
