package org.buaa.rag.service;

import java.util.List;
import java.util.Optional;

import org.buaa.rag.dto.RetrievalMatch;

public interface SemanticCacheService {

    Optional<CacheHit> find(String query);

    void put(String query, String response, List<RetrievalMatch> sources);

    record CacheHit(String response, List<RetrievalMatch> sources, double similarity) {
    }
}
