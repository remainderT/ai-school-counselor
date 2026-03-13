package org.buaa.rag.module.chunk;

import java.util.List;

/**
 * 分块策略接口
 */
public interface ChunkingStrategy {

    ChunkingMode getType();

    List<String> chunk(String text, ChunkingOptions options);
}

