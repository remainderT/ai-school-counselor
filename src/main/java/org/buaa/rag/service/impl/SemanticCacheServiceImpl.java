package org.buaa.rag.service.impl;

import org.buaa.rag.config.RagConfiguration;
import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.tool.VectorEncoding;
import org.buaa.rag.service.SemanticCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SemanticCacheServiceImpl implements SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheServiceImpl.class);

    private final VectorEncoding embeddingPort;
    private final RagConfiguration ragConfiguration;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public SemanticCacheServiceImpl(VectorEncoding embeddingPort,
                                    RagConfiguration ragConfiguration) {
        this.embeddingPort = embeddingPort;
        this.ragConfiguration = ragConfiguration;
    }

    @Override
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
            double score = cosine(queryVector, entry.queryVector());
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

    @Override
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
        RagConfiguration.SemanticCache cfg = ragConfiguration.getSemanticCache();
        return cfg != null && cfg.isEnabled();
    }

    private double minSimilarity() {
        RagConfiguration.SemanticCache cfg = ragConfiguration.getSemanticCache();
        if (cfg == null) {
            return 0.92;
        }
        return cfg.getMinSimilarity();
    }

    private long ttlMillis() {
        RagConfiguration.SemanticCache cfg = ragConfiguration.getSemanticCache();
        if (cfg == null) {
            return 120L * 60 * 1000;
        }
        return Math.max(1, cfg.getTtlMinutes()) * 60 * 1000;
    }

    private int maxEntries() {
        RagConfiguration.SemanticCache cfg = ragConfiguration.getSemanticCache();
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

    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        double cos = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        if (Double.isNaN(cos) || Double.isInfinite(cos)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (cos + 1.0) / 2.0));
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
