package org.buaa.rag.core.online.cache;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.buaa.rag.common.util.VectorMathUtils;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.offline.index.VectorEncoding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SemanticCacheService {

    public record CacheHit(String response, List<RetrievalMatch> sources, double similarity) {
    }

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final VectorEncoding embeddingPort;
    private final RagProperties ragProperties;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public SemanticCacheService(VectorEncoding embeddingPort,
                                RagProperties ragProperties) {
        this.embeddingPort = embeddingPort;
        this.ragProperties = ragProperties;
    }

    public Optional<CacheHit> find(String query) {
        if (!enabled() || query == null || query.isBlank()) {
            return Optional.empty();
        }

        float[] queryVector = encodeSingle(query);
        if (queryVector == null || queryVector.length == 0) {
            return Optional.empty();
        }

        evictExpired();

        CacheEntry best = null;
        double bestScore = 0.0;
        for (CacheEntry entry : cache.values()) {
            double score = VectorMathUtils.cosine(queryVector, entry.queryVector());
            if (score > bestScore) {
                bestScore = score;
                best = entry;
            }
        }

        if (best == null || bestScore < minSimilarity()) {
            return Optional.empty();
        }

        List<RetrievalMatch> copiedSources = copySources(best.sources());
        return Optional.of(new CacheHit(best.response(), copiedSources, bestScore));
    }

    public void put(String query, String response, List<RetrievalMatch> sources) {
        if (!enabled() || query == null || query.isBlank() || response == null || response.isBlank()) {
            return;
        }

        float[] queryVector = encodeSingle(query);
        if (queryVector == null || queryVector.length == 0) {
            return;
        }

        List<RetrievalMatch> copiedSources = copySources(sources);
        CacheEntry entry = new CacheEntry(
            query.trim(),
            queryVector,
            response,
            copiedSources,
            Instant.now().toEpochMilli()
        );
        cache.put(UUID.randomUUID().toString(), entry);
        trimIfNeeded();
    }

    private boolean enabled() {
        RagProperties.SemanticCache cfg = ragProperties.getSemanticCache();
        return cfg != null && cfg.isEnabled();
    }

    private double minSimilarity() {
        RagProperties.SemanticCache cfg = ragProperties.getSemanticCache();
        if (cfg == null) {
            return 0.92;
        }
        return cfg.getMinSimilarity();
    }

    private long ttlMillis() {
        RagProperties.SemanticCache cfg = ragProperties.getSemanticCache();
        if (cfg == null) {
            return 120L * 60 * 1000;
        }
        return Math.max(1, cfg.getTtlMinutes()) * 60 * 1000;
    }

    private int maxEntries() {
        RagProperties.SemanticCache cfg = ragProperties.getSemanticCache();
        if (cfg == null) {
            return 300;
        }
        return Math.max(10, cfg.getMaxEntries());
    }

    private void evictExpired() {
        long now = Instant.now().toEpochMilli();
        long ttl = ttlMillis();
        cache.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > ttl);
    }

    private void trimIfNeeded() {
        int max = maxEntries();
        if (cache.size() <= max) {
            return;
        }
        int overflow = cache.size() - max;
        cache.entrySet().stream()
            .sorted(Comparator.comparingLong(e -> e.getValue().createdAt()))
            .limit(overflow)
            .map(Map.Entry::getKey)
            .toList()
            .forEach(cache::remove);
    }

    private float[] encodeSingle(String query) {
        try {
            List<float[]> vectors = embeddingPort.encode(List.of(query));
            if (vectors == null || vectors.isEmpty()) {
                return null;
            }
            return vectors.get(0);
        } catch (Exception e) {
            log.debug("语义缓存向量编码失败: {}", e.getMessage());
            return null;
        }
    }

    private List<RetrievalMatch> copySources(List<RetrievalMatch> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }

        List<RetrievalMatch> copied = new ArrayList<>(sources.size());
        for (RetrievalMatch source : sources) {
            if (source == null) {
                continue;
            }
            RetrievalMatch clone = new RetrievalMatch(
                source.getFileMd5(),
                source.getChunkId(),
                source.getTextContent(),
                source.getRelevanceScore()
            );
            clone.setSourceFileName(source.getSourceFileName());
            copied.add(clone);
        }
        return copied;
    }

    private record CacheEntry(String query,
                              float[] queryVector,
                              String response,
                              List<RetrievalMatch> sources,
                              long createdAt) {
    }
}
