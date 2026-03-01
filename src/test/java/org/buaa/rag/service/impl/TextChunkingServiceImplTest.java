package org.buaa.rag.service.impl;

import java.util.List;

import org.buaa.rag.core.chunk.ChunkingStrategy;
import org.buaa.rag.core.chunk.ChunkingStrategyFactory;
import org.buaa.rag.core.chunk.strategy.FixedSizeChunkingStrategy;
import org.buaa.rag.core.chunk.strategy.ParagraphChunkingStrategy;
import org.buaa.rag.core.chunk.strategy.SentenceChunkingStrategy;
import org.buaa.rag.core.chunk.strategy.StructureAwareChunkingStrategy;
import org.buaa.rag.properties.FIleParseProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextChunkingServiceImplTest {

    @Test
    void shouldUseSentenceStrategyWhenConfigured() {
        FIleParseProperties properties = new FIleParseProperties();
        properties.setChunkMode("sentence");
        properties.setChunkSize(8);
        properties.setOverlapSize(0);

        List<ChunkingStrategy> strategies = List.of(
            new FixedSizeChunkingStrategy(),
            new StructureAwareChunkingStrategy(),
            new ParagraphChunkingStrategy(),
            new SentenceChunkingStrategy()
        );
        ChunkingStrategyFactory factory = new ChunkingStrategyFactory(strategies);
        factory.init();

        TextChunkingServiceImpl service = new TextChunkingServiceImpl(properties, factory);

        List<String> chunks = service.chunk("第一句。第二句。第三句。第四句。", 6);

        assertEquals(List.of("第一句。", "第二句。", "第三句。", "第四句。"), chunks);
    }
}
