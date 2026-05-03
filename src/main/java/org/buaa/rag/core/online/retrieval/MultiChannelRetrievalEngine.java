package org.buaa.rag.core.online.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.trace.RagTraceNode;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.retrieval.channel.SearchChannel;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelResult;
import org.buaa.rag.core.online.retrieval.channel.SearchContext;
import org.buaa.rag.core.online.retrieval.postprocessor.SearchResultPostProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 多通道检索引擎：并行调度所有激活的 {@link SearchChannel}，
 * 合并结果后按顺序执行 {@link SearchResultPostProcessor} 链。
 */
@Slf4j
@Service
public class MultiChannelRetrievalEngine {

    private final List<SearchChannel> channels;
    private final List<SearchResultPostProcessor> processors;
    private final Executor executor;

    public MultiChannelRetrievalEngine(List<SearchChannel> channels,
                                       List<SearchResultPostProcessor> processors,
                                       @Qualifier("retrievalChannelExecutor") Executor executor) {
        this.channels = channels;
        this.processors = processors;
        this.executor = executor;
    }

    public List<RetrievalMatch> retrieve(String userId,
                                         String query,
                                         int topK,
                                         IntentDecision intentDecision) {
        return retrieve(userId, query, topK, intentDecision, null);
    }

    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public List<RetrievalMatch> retrieve(String userId,
                                         String query,
                                         int topK,
                                         IntentDecision intentDecision,
                                         List<IntentDecision> intentDecisions) {
        SearchContext ctx = SearchContext.builder()
            .userId(userId)
            .originalQuery(query)
            .rewrittenQuery(query)
            .topK(topK)
            .intentDecision(intentDecision)
            .intentDecisions(intentDecisions == null ? List.of() : intentDecisions)
            .build();
        return retrieve(ctx);
    }

    public List<RetrievalMatch> retrieve(SearchContext ctx) {
        long start = System.nanoTime();
        // 1. 筛选可激活的通道并按优先级排序
        List<SearchChannel> active = channels.stream()
            .filter(ch -> tryCanActivate(ch, ctx))
            .sorted(Comparator.comparingInt(SearchChannel::getPriority))
            .toList();
        if (active.isEmpty()) {
            return List.of();
        }

        // 2. 并行执行各通道
        long channelStart = System.nanoTime();
        List<CompletableFuture<SearchChannelResult>> futures = active.stream()
            .map(ch -> CompletableFuture.supplyAsync(() -> invokeChannel(ch, ctx), executor))
            .toList();

        List<SearchChannelResult> channelResults = futures.stream()
            .map(f -> {
                try { return f.join(); }
                catch (Exception ex) {
                    log.warn("等待检索通道结果失败", ex);
                    return null;
                }
                })
                .filter(r -> r != null)
                .toList();
        if (channelResults.isEmpty()) {
            return List.of();
        }
        long channelElapsed = elapsedMs(channelStart);

        // 3. 扁平合并所有通道的匹配结果
        List<RetrievalMatch> merged = channelResults.stream()
            .flatMap(r -> r.getMatches() == null ? Stream.empty() : r.getMatches().stream())
            .toList();

        // 4. 按序执行后处理链
        List<SearchResultPostProcessor> activeProcessors = processors.stream()
            .filter(p -> tryShouldApply(p, ctx))
            .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
            .toList();

        long postProcessStart = System.nanoTime();
        List<RetrievalMatch> result = new ArrayList<>(merged);
        for (SearchResultPostProcessor proc : activeProcessors) {
            try {
                result = proc.apply(result, channelResults, ctx);
            } catch (Exception ex) {
                log.warn("检索后处理器执行失败: {}", proc.getName(), ex);
            }
        }
        log.info("多通道检索引擎完成 | query='{}' | activeChannels={} | merged={} | final={} | channel耗时={}ms | post耗时={}ms | 总耗时={}ms",
            compact(ctx.getOriginalQuery()), active.size(), merged.size(), result.size(),
            channelElapsed, elapsedMs(postProcessStart), elapsedMs(start));
        return result;
    }

    private SearchChannelResult invokeChannel(SearchChannel channel, SearchContext ctx) {
        long start = System.nanoTime();
        try {
            SearchChannelResult result = channel.execute(ctx);
            if (result != null) {
                long elapsed = elapsedMs(start);
                int count = result.getMatches() == null ? 0 : result.getMatches().size();
                log.info("检索通道完成 | channel={} | type={} | count={} | 耗时={}ms",
                    channel.getName(), channel.getType(), count, elapsed);
                return result;
            }
            log.info("检索通道返回空结果 | channel={} | type={} | 耗时={}ms",
                channel.getName(), channel.getType(), elapsedMs(start));
            return emptyResult(channel);
        } catch (Exception ex) {
            log.warn("检索通道执行失败 | channel={} | type={} | 耗时={}ms",
                channel.getName(), channel.getType(), elapsedMs(start), ex);
            return emptyResult(channel);
        }
    }

    private SearchChannelResult emptyResult(SearchChannel channel) {
        return SearchChannelResult.builder()
            .channelType(channel.getType())
            .channelName(channel.getName())
            .matches(List.of())
            .confidence(0.0)
            .elapsedMs(0L)
            .build();
    }

    private boolean tryCanActivate(SearchChannel ch, SearchContext ctx) {
        try { return ch.canActivate(ctx); }
        catch (Exception ex) {
            log.warn("判断通道启用状态失败: {}", ch.getName(), ex);
            return false;
        }
    }

    private boolean tryShouldApply(SearchResultPostProcessor p, SearchContext ctx) {
        try { return p.shouldApply(ctx); }
        catch (Exception ex) {
            log.warn("判断后处理器启用状态失败: {}", p.getName(), ex);
            return false;
        }
    }

    private String compact(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
