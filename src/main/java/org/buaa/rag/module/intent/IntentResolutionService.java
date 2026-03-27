package org.buaa.rag.module.intent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import org.buaa.rag.dto.IntentDecision;
import org.buaa.rag.properties.IntentResolutionProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class IntentResolutionService {

    private final IntentRouterService intentRouterService;
    private final IntentResolutionProperties properties;

    @Qualifier("intentResolutionExecutor")
    private final Executor intentResolutionExecutor;

    public List<SubQueryIntent> resolve(String userId, List<String> subQueries) {
        if (subQueries == null || subQueries.isEmpty()) {
            return List.of();
        }

        List<String> normalized = subQueries.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .toList();
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<CompletableFuture<SubQueryIntent>> futures = normalized.stream()
            .map(subQuery -> CompletableFuture.supplyAsync(
                () -> classifySingle(userId, subQuery),
                intentResolutionExecutor
            ))
            .toList();

        List<SubQueryIntent> resolved = futures.stream()
            .map(CompletableFuture::join)
            .toList();
        return capTotalCandidates(resolved, Math.max(1, properties.getMaxTotalCandidates()));
    }

    public List<IntentDecision> resolveForQuery(String userId, String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        int perQueryMax = Math.max(1, properties.getPerQueryMaxCandidates());
        double minScore = Math.max(0.0, properties.getMinScore());
        List<IntentDecision> ranked = intentRouterService.rankIntentCandidates(userId, query, perQueryMax, minScore);
        if (!ranked.isEmpty()) {
            return ranked;
        }
        IntentDecision fallback = intentRouterService.decide(userId, query);
        if (fallback == null) {
            return List.of();
        }
        return List.of(fallback);
    }

    private SubQueryIntent classifySingle(String userId, String subQuery) {
        List<IntentDecision> candidates = resolveForQuery(userId, subQuery);
        return new SubQueryIntent(subQuery, candidates);
    }

    private List<SubQueryIntent> capTotalCandidates(List<SubQueryIntent> resolved, int maxTotal) {
        int total = resolved.stream()
            .map(SubQueryIntent::candidates)
            .filter(list -> list != null)
            .mapToInt(List::size)
            .sum();
        if (total <= maxTotal) {
            return resolved;
        }

        List<PickedCandidate> guaranteed = new ArrayList<>();
        for (int i = 0; i < resolved.size(); i++) {
            List<IntentDecision> candidates = resolved.get(i).candidates();
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            guaranteed.add(new PickedCandidate(i, candidates.get(0)));
        }
        int remaining = Math.max(0, maxTotal - guaranteed.size());

        List<PickedCandidate> optional = new ArrayList<>();
        for (int i = 0; i < resolved.size(); i++) {
            List<IntentDecision> candidates = resolved.get(i).candidates();
            if (candidates == null || candidates.size() < 2) {
                continue;
            }
            for (int j = 1; j < candidates.size(); j++) {
                optional.add(new PickedCandidate(i, candidates.get(j)));
            }
        }
        optional.sort(Comparator.comparingDouble(
            c -> -(c.decision().getConfidence() == null ? 0.0 : c.decision().getConfidence())
        ));

        List<PickedCandidate> picked = new ArrayList<>(guaranteed);
        for (PickedCandidate candidate : optional) {
            if (remaining <= 0) {
                break;
            }
            picked.add(candidate);
            remaining--;
        }

        Map<Integer, List<IntentDecision>> grouped = picked.stream()
            .collect(Collectors.groupingBy(
                PickedCandidate::index,
                Collectors.mapping(PickedCandidate::decision, Collectors.toList())
            ));

        List<SubQueryIntent> capped = new ArrayList<>(resolved.size());
        for (int i = 0; i < resolved.size(); i++) {
            SubQueryIntent origin = resolved.get(i);
            List<IntentDecision> candidates = grouped.getOrDefault(i, List.of());
            capped.add(new SubQueryIntent(origin.subQuery(), candidates));
        }
        return capped;
    }

    private record PickedCandidate(int index, IntentDecision decision) {
    }
}
