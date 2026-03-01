package org.buaa.rag.core.chunk.strategy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.core.chunk.ChunkingOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureAwareChunkingStrategyTest {

    @Test
    void shouldPackContentByBlockBoundaries() {
        String para1 = "alpha ".repeat(20);
        String para2 = "beta ".repeat(45);
        String text = "# H1\n" + para1 + "\n\n## H2\n" + para2 + "\n";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("targetChars", 120);
        metadata.put("maxChars", 240);
        metadata.put("minChars", 80);
        metadata.put("overlapChars", 0);

        StructureAwareChunkingStrategy strategy = new StructureAwareChunkingStrategy();
        List<String> chunks = strategy.chunk(text, ChunkingOptions.builder()
            .chunkSize(64)
            .overlapSize(0)
            .metadata(metadata)
            .build());

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).startsWith("# H1"));
        assertTrue(chunks.get(1).startsWith("## H2"));
    }
}
