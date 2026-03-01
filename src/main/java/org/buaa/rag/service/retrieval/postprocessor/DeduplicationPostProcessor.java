package org.buaa.rag.service.retrieval.postprocessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.properties.SearchChannelProperties;
import org.buaa.rag.service.retrieval.channel.SearchChannelResult;
import org.buaa.rag.service.retrieval.channel.SearchChannelType;
import org.buaa.rag.service.retrieval.channel.SearchContext;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

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
    public boolean isEnabled(SearchContext context) {
        return properties.getPostProcessor().isDeduplicate();
    }

    @Override
    public List<RetrievalMatch> process(List<RetrievalMatch> matches,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        Map<String, RetrievalMatch> bucket = new LinkedHashMap<>();

        List<SearchChannelResult> sortedResults = new ArrayList<>(results);
        sortedResults.sort((a, b) -> Integer.compare(
            getChannelPriority(a.getChannelType()),
            getChannelPriority(b.getChannelType())
        ));

        for (SearchChannelResult result : sortedResults) {
            if (result == null || result.getMatches() == null) {
                continue;
            }
            for (RetrievalMatch match : result.getMatches()) {
                if (match == null) {
                    continue;
                }
                String key = buildMatchKey(match);
                RetrievalMatch existing = bucket.get(key);
                if (existing == null) {
                    bucket.put(key, match);
                    continue;
                }
                double existingScore = existing.getRelevanceScore() == null ? 0.0 : existing.getRelevanceScore();
                double currentScore = match.getRelevanceScore() == null ? 0.0 : match.getRelevanceScore();
                if (currentScore > existingScore) {
                    bucket.put(key, match);
                }
            }
        }

        return new ArrayList<>(bucket.values());
    }

    private int getChannelPriority(SearchChannelType channelType) {
        if (channelType == SearchChannelType.INTENT_DIRECTED) {
            return 1;
        }
        if (channelType == SearchChannelType.VECTOR_GLOBAL) {
            return 2;
        }
        return 99;
    }

    private String buildMatchKey(RetrievalMatch match) {
        String fileMd5 = match.getFileMd5() == null ? "unknown" : match.getFileMd5();
        String chunkId = match.getChunkId() == null ? "0" : match.getChunkId().toString();
        return fileMd5 + ":" + chunkId;
    }
}
