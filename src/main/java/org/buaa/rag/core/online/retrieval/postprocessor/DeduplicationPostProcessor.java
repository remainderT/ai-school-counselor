package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.properties.SearchChannelProperties;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelResult;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelType;
import org.buaa.rag.core.online.retrieval.channel.SearchContext;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 跨通道去重：按通道优先级遍历结果，同一 matchKey 只保留最高分的那条。
 */
@Component
@RequiredArgsConstructor
public class DeduplicationPostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties properties;

    @Override
    public String getName() {
        return "deduplication";
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public boolean shouldApply(SearchContext context) {
        return properties.getPostProcessor().isDeduplicate();
    }

    @Override
    public List<RetrievalMatch> apply(List<RetrievalMatch> matches,
                                      List<SearchChannelResult> channelResults,
                                      SearchContext context) {
        List<SearchChannelResult> ordered = new ArrayList<>(channelResults);
        ordered.sort((a, b) -> Integer.compare(
            channelWeight(a.getChannelType()),
            channelWeight(b.getChannelType())
        ));

        Map<String, RetrievalMatch> seen = new LinkedHashMap<>();
        for (SearchChannelResult result : ordered) {
            if (result == null || result.getMatches() == null) {
                continue;
            }
            for (RetrievalMatch match : result.getMatches()) {
                if (match == null) {
                    continue;
                }
                String key = match.matchKey();
                RetrievalMatch prev = seen.get(key);
                if (prev == null) {
                    seen.put(key, match);
                } else {
                    double prevScore = prev.getRelevanceScore() == null ? 0.0 : prev.getRelevanceScore();
                    double curScore = match.getRelevanceScore() == null ? 0.0 : match.getRelevanceScore();
                    if (curScore > prevScore) {
                        seen.put(key, match);
                    }
                }
            }
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * 通道权重：数字越小越优先保留。
     */
    private int channelWeight(SearchChannelType type) {
        return switch (type) {
            case INTENT_DIRECTED -> 1;
            case VECTOR_GLOBAL -> 2;
        };
    }
}
