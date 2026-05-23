package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelResult;
import org.buaa.rag.core.online.retrieval.channel.SearchContext;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.properties.SearchChannelProperties;
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
    private final SearchChannelProperties searchChannelProperties;

    @Override
    public String label() {
        return "rrf-fusion";
    }

    @Override
    public int stage() {
        return 10;
    }

    @Override
    public boolean isActive(SearchContext ctx) {
        return ragProperties.getFusion().isEnabled()
            && searchChannelProperties.getPostProcessor().isRrfFusion();
    }

    @Override
    public List<RetrievalMatch> process(List<RetrievalMatch> candidates,
                                        List<SearchChannelResult> channelOutputs,
                                        SearchContext ctx) {
        if (channelOutputs == null || channelOutputs.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }

        int rrfK = Math.max(1, ragProperties.getFusion().getRrfK());
        Map<String, RetrievalMatch> bestMatch = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new LinkedHashMap<>();

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
                String key = match.matchKey();
                rrfScores.merge(key, 1.0 / (rrfK + rank + 1), Double::sum);
                bestMatch.merge(key, match, (left, right) -> relevance(left) >= relevance(right) ? left : right);
            }
        }

        List<RetrievalMatch> fused = new ArrayList<>();
        for (Map.Entry<String, RetrievalMatch> entry : bestMatch.entrySet()) {
            RetrievalMatch match = entry.getValue();
            match.setRelevanceScore(rrfScores.getOrDefault(entry.getKey(), 0.0));
            fused.add(match);
        }
        fused.sort((left, right) -> Double.compare(relevance(right), relevance(left)));
        log.debug("RRF融合完成: 通道数={} | 输入={} | 输出={}",
            channelOutputs.size(), candidates == null ? 0 : candidates.size(), fused.size());
        return fused;
    }

    private double relevance(RetrievalMatch match) {
        return match == null || match.getRelevanceScore() == null ? 0.0 : match.getRelevanceScore();
    }
}
