package org.buaa.rag.core.chunk;

/**
 * 分块模式
 */
public enum ChunkingMode {

    FIXED_SIZE("fixed_size"),
    STRUCTURE_AWARE("structure_aware"),
    SENTENCE("sentence"),
    PARAGRAPH("paragraph");

    private final String value;

    ChunkingMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ChunkingMode fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return STRUCTURE_AWARE;
        }
        String normalized = raw.trim().toLowerCase().replace('-', '_');
        for (ChunkingMode mode : values()) {
            if (mode.value.equals(normalized) || mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        return STRUCTURE_AWARE;
    }
}

