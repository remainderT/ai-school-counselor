package org.buaa.rag.core.chunk;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 分块策略工厂
 */
@Component
@RequiredArgsConstructor
public class ChunkingStrategyFactory {

    private final List<ChunkingStrategy> chunkingStrategies;
    private volatile Map<ChunkingMode, ChunkingStrategy> strategyMap = Map.of();

    @PostConstruct
    public void init() {
        Map<ChunkingMode, ChunkingStrategy> map = new EnumMap<>(ChunkingMode.class);
        for (ChunkingStrategy strategy : chunkingStrategies) {
            map.put(strategy.getType(), strategy);
        }
        strategyMap = Map.copyOf(map);
    }

    public Optional<ChunkingStrategy> findStrategy(ChunkingMode mode) {
        if (mode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(strategyMap.get(mode));
    }

    public ChunkingStrategy requireStrategy(ChunkingMode mode) {
        return findStrategy(mode)
            .orElseThrow(() -> new IllegalArgumentException("Unknown chunking mode: " + mode));
    }
}

