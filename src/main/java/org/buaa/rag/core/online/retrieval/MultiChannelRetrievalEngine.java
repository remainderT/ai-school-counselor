package org.buaa.rag.core.online.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.online.trace.RagTraceNode;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.retrieval.channel.SearchChannel;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelResult;
import org.buaa.rag.core.online.retrieval.channel.SearchContext;
import org.buaa.rag.core.online.retrieval.postprocessor.SearchResultPostProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 多通道检索引擎。
 *
 * <p>根据当前请求上下文动态筛选可用通道，并行执行检索后将结果汇集，
 * 再依次流经后处理链（去重 → 精排 → 截断）得到最终结果。
 *
 * <p>引擎本身不包含任何检索逻辑——检索策略全部由 {@link SearchChannel}
 * 实现承载，引擎仅负责调度、容错和编排。
 */
@Slf4j
@Service
public class MultiChannelRetrievalEngine {

    /** 单通道最大等待时间（秒），超时后该通道结果被丢弃 */
    private static final int CHANNEL_TIMEOUT_SECONDS = 30;

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

    /** 便捷入口（单意图场景） */
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
        return dispatch(ctx);
    }

    /**
     * 核心调度：筛选 → 并行检索 → 合并 → 后处理。
     */
    public List<RetrievalMatch> dispatch(SearchContext ctx) {
        long t0 = System.nanoTime();

        // 1. 筛选可激活的通道并按优先级排序
        List<SearchChannel> applicable = channels.stream()
                .filter(ch -> safeIsApplicable(ch, ctx))
                .sorted(Comparator.comparingInt(SearchChannel::dispatchOrder))
                .toList();
        if (applicable.isEmpty()) {
            log.info("无可用检索通道 | query='{}'", truncate(ctx.getOriginalQuery(), 80));
            return List.of();
        }

        // 2. 并行分发到各通道（带超时保护）
        long t1 = System.nanoTime();
        List<CompletableFuture<SearchChannelResult>> tasks = applicable.stream()
                .map(ch -> CompletableFuture
                        .supplyAsync(() -> invokeChannel(ch, ctx), executor)
                        .orTimeout(CHANNEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            log.warn("检索通道超时或异常 | channel={} | error={}",
                                    ch.channelId(), ex.getMessage());
                            return SearchChannelResult.empty(ch.channelType(), ch.channelId());
                        }))
                .toList();

        List<SearchChannelResult> channelOutputs = tasks.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();
        long channelCostMs = nanosToMs(t1);

        if (channelOutputs.isEmpty()) {
            return List.of();
        }

        // 3. 扁平合并
        List<RetrievalMatch> merged = channelOutputs.stream()
                .flatMap(r -> r.hits() == null ? Stream.empty() : r.hits().stream())
                .toList();

        // 4. 执行后处理链
        long t2 = System.nanoTime();
        List<SearchResultPostProcessor> activeProcessors = processors.stream()
                .filter(p -> safeIsActive(p, ctx))
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::stage))
                .toList();

        List<RetrievalMatch> result = new ArrayList<>(merged);
        for (SearchResultPostProcessor proc : activeProcessors) {
            try {
                result = proc.process(result, channelOutputs, ctx);
            } catch (Exception ex) {
                log.warn("后处理器执行异常: processor={} | error={}", proc.label(), ex.getMessage(), ex);
            }
        }

        log.info("多通道检索完成 | query='{}' | 通道数={} | 合并={} | 最终={} | 通道耗时={}ms | 后处理耗时={}ms | 总计={}ms",
                truncate(ctx.getOriginalQuery(), 60),
                applicable.size(), merged.size(), result.size(),
                channelCostMs, nanosToMs(t2), nanosToMs(t0));

        return result;
    }

    // ────────────────── 内部方法 ──────────────────

    private SearchChannelResult invokeChannel(SearchChannel channel, SearchContext ctx) {
        long start = System.nanoTime();
        try {
            SearchChannelResult result = channel.fetch(ctx);
            if (result != null && result.hasHits()) {
                log.info("通道检索完成 | channel={} | hits={} | topScore={} | 耗时={}ms",
                        channel.channelId(), result.hitCount(),
                        String.format("%.4f", result.topScore()), nanosToMs(start));
                return result;
            }
            return SearchChannelResult.empty(channel.channelType(), channel.channelId());
        } catch (Exception ex) {
            log.warn("通道检索异常 | channel={} | 耗时={}ms | error={}",
                    channel.channelId(), nanosToMs(start), ex.getMessage(), ex);
            return SearchChannelResult.empty(channel.channelType(), channel.channelId());
        }
    }

    private boolean safeIsApplicable(SearchChannel ch, SearchContext ctx) {
        try {
            return ch.isApplicable(ctx);
        } catch (Exception ex) {
            log.warn("通道激活判断异常: channel={}", ch.channelId(), ex);
            return false;
        }
    }

    private boolean safeIsActive(SearchResultPostProcessor p, SearchContext ctx) {
        try {
            return p.isActive(ctx);
        } catch (Exception ex) {
            log.warn("后处理器激活判断异常: processor={}", p.label(), ex);
            return false;
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > maxLen ? compact.substring(0, maxLen) + "…" : compact;
    }

    private long nanosToMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
