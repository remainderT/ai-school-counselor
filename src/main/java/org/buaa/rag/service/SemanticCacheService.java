package org.buaa.rag.service;

import org.buaa.rag.dto.RetrievalMatch;

import java.util.List;
import java.util.Optional;

public interface SemanticCacheService {

    Optional<CacheHit> find(String query);

    void put(String query, String response, List<RetrievalMatch> sources);

    record CacheHit(String response, List<RetrievalMatch> sources, double similarity) {
    }
}
