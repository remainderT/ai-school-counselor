package org.buaa.rag.module.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import org.buaa.rag.dto.IntentDecision;
import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.module.retrieval.channel.SearchChannel;
import org.buaa.rag.module.retrieval.channel.SearchChannelResult;
import org.buaa.rag.module.retrieval.channel.SearchContext;
import org.buaa.rag.module.retrieval.postprocessor.SearchResultPostProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 多通道检索引擎
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiChannelRetrievalEngine {

    private final List<SearchChannel> searchChannels;
    private final List<SearchResultPostProcessor> postProcessors;

    @Qualifier("retrievalChannelExecutor")
    private final Executor retrievalChannelExecutor;

    public List<RetrievalMatch> retrieve(String userId,
                                         String query,
                                         int topK,
                                         IntentDecision intentDecision) {
        SearchContext context = SearchContext.builder()
            .userId(userId)
            .originalQuery(query)
            .rewrittenQuery(query)
            .topK(topK)
            .intentDecision(intentDecision)
            .build();
        return retrieve(context);
    }

    public List<RetrievalMatch> retrieve(SearchContext context) {
        List<SearchChannel> enabledChannels = searchChannels.stream()
            .filter(channel -> safeChannelEnabled(channel, context))
            .sorted(Comparator.comparingInt(SearchChannel::getPriority))
            .toList();

        if (enabledChannels.isEmpty()) {
            return List.of();
        }

        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
            .map(channel -> CompletableFuture.supplyAsync(
                () -> runChannel(channel, context), retrievalChannelExecutor
            ))
            .toList();

        List<SearchChannelResult> channelResults = futures.stream()
            .map(future -> {
                try {
                    return future.join();
                } catch (Exception e) {
                    log.warn("等待检索通道结果失败", e);
                    return null;
                }
            })
            .filter(result -> result != null)
            .toList();

        if (channelResults.isEmpty()) {
            return List.of();
        }

        List<RetrievalMatch> merged = channelResults.stream()
            .flatMap(result -> result.getMatches() == null
                ? Stream.empty()
                : result.getMatches().stream())
            .toList();

        List<SearchResultPostProcessor> enabledProcessors = postProcessors.stream()
            .filter(processor -> safeProcessorEnabled(processor, context))
            .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
            .toList();

        List<RetrievalMatch> current = new ArrayList<>(merged);
        for (SearchResultPostProcessor processor : enabledProcessors) {
            try {
                current = processor.process(current, channelResults, context);
            } catch (Exception e) {
                log.warn("检索后处理器执行失败: {}", processor.getName(), e);
            }
        }
        return current;
    }

    private SearchChannelResult runChannel(SearchChannel channel, SearchContext context) {
        try {
            SearchChannelResult result = channel.search(context);
            if (result == null) {
                return SearchChannelResult.builder()
                    .channelType(channel.getType())
                    .channelName(channel.getName())
                    .matches(List.of())
                    .confidence(0.0)
                    .latencyMs(0L)
                    .build();
            }
            return result;
        } catch (Exception e) {
            log.warn("检索通道执行失败: {}", channel.getName(), e);
            return SearchChannelResult.builder()
                .channelType(channel.getType())
                .channelName(channel.getName())
                .matches(List.of())
                .confidence(0.0)
                .latencyMs(0L)
                .build();
        }
    }

    private boolean safeChannelEnabled(SearchChannel channel, SearchContext context) {
        try {
            return channel.isEnabled(context);
        } catch (Exception e) {
            log.warn("判断通道启用状态失败: {}", channel.getName(), e);
            return false;
        }
    }

    private boolean safeProcessorEnabled(SearchResultPostProcessor processor, SearchContext context) {
        try {
            return processor.isEnabled(context);
        } catch (Exception e) {
            log.warn("判断后处理器启用状态失败: {}", processor.getName(), e);
            return false;
        }
    }
}
