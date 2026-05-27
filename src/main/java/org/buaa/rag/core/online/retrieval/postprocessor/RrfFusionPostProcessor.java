package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelResult;
import org.buaa.rag.core.online.retrieval.channel.SearchContext;
import org.buaa.rag.properties.RagProperties;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RRF 倒数排名融合处理器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RrfFusionPostProcessor implements SearchResultPostProcessor {

    private final RagProperties ragProperties;

    @Override
    public String label() {
        return "rrf-fusion";
    }

    @Override
    public int stage() {
        return 20;
    }

    @Override
    public boolean isActive(SearchContext ctx) {
        return ragProperties.getFusion().isEnabled();
    }

    @Override
    public List<RetrievalMatch> process(List<RetrievalMatch> candidates,
                                        List<SearchChannelResult> channelOutputs,
                                        SearchContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }

        int rrfK = Math.max(1, ragProperties.getFusion().getRrfK());
        Map<String, Double> rrfScores = buildRrfScores(channelOutputs, rrfK);
        List<RetrievalMatch> fused = new ArrayList<>(candidates.size());

        for (RetrievalMatch match : candidates) {
            if (match == null) {
                continue;
            }
            double score = rrfScores.getOrDefault(match.matchKey(), relevance(match));
            match.setRelevanceScore(score);
            fused.add(match);
        }
        fused.sort((left, right) -> Double.compare(relevance(right), relevance(left)));
        log.debug("RRF融合完成: 输入={} | 输出={}",
            candidates.size(), fused.size());
        return fused;
    }

    private Map<String, Double> buildRrfScores(List<SearchChannelResult> channelOutputs, int rrfK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        if (channelOutputs == null || channelOutputs.isEmpty()) {
            return scores;
        }
        for (SearchChannelResult output : channelOutputs) {
            if (output == null || output.hits() == null || output.hits().isEmpty()) {
                continue;
            }
            List<RetrievalMatch> hits = output.hits();
            for (int rank = 0; rank < hits.size(); rank++) {
                RetrievalMatch match = hits.get(rank);
                if (match == null) {
                    continue;
                }
                scores.merge(match.matchKey(), 1.0 / (rrfK + rank + 1), Double::sum);
            }
        }
        return scores;
    }

    private double relevance(RetrievalMatch match) {
        return match == null || match.getRelevanceScore() == null ? 0.0 : match.getRelevanceScore();
    }
}
