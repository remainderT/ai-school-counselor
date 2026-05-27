package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.buaa.rag.common.enums.SearchChannelType;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.properties.SearchChannelProperties;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelResult;
import org.buaa.rag.core.online.retrieval.channel.SearchContext;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 跨通道去重处理器。
 *
 * <p>当同一文档片段被多个通道同时召回时，需要去重以避免上下文冗余。
 * 本处理器的合并策略：
 * <ol>
 *   <li>按 {@link RetrievalMatch#matchKey()} 对所有命中做分组</li>
 *   <li>优先保留高可信通道结果；同通道重复时再比较原始分数</li>
 *   <li>保留每组最优记录作为后续 RRF 融合的代表项</li>
 * </ol>
 *
 * <p>该阶段不强行比较不同检索通道的异构分数，跨通道排序交给 RRF 与 Rerank 完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeduplicationPostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties properties;

    @Override
    public String label() {
        return "cross-channel-dedup";
    }

    @Override
    public int stage() {
        return 10;
    }

    @Override
    public boolean isActive(SearchContext ctx) {
        return properties.getPostProcessor().isDeduplicate();
    }

    @Override
    public List<RetrievalMatch> process(List<RetrievalMatch> candidates,
                                        List<SearchChannelResult> channelOutputs,
                                        SearchContext ctx) {
        if (candidates == null || candidates.size() <= 1) {
            return candidates == null ? List.of() : candidates;
        }

        // 按 matchKey 分组，每组选通道优先级最高的代表项
        int beforeCount = candidates.size();
        Collection<RetrievalMatch> deduplicated = candidates.stream()
                .collect(Collectors.toMap(
                        RetrievalMatch::matchKey,
                        match -> match,
                        (existing, incoming) ->
                                compareRepresentative(incoming, existing) > 0
                                        ? incoming : existing,
                        java.util.LinkedHashMap::new
                ))
                .values();

        // 按通道优先级与同通道原始分数排序，给后续 RRF 一个稳定输入
        List<RetrievalMatch> result = deduplicated.stream()
                .sorted(this::compareRepresentativeDescending)
                .toList();

        if (result.size() < beforeCount) {
            log.debug("跨通道去重: {} → {} 条（移除 {} 条重复）",
                    beforeCount, result.size(), beforeCount - result.size());
        }
        return result;
    }

    private int compareRepresentative(RetrievalMatch left, RetrievalMatch right) {
        int priorityCompare = Integer.compare(
            channelPriority(right == null ? null : right.getChannelType()),
            channelPriority(left == null ? null : left.getChannelType())
        );
        if (priorityCompare != 0) {
            return priorityCompare;
        }
        return Double.compare(relevance(left), relevance(right));
    }

    private int compareRepresentativeDescending(RetrievalMatch left, RetrievalMatch right) {
        return -compareRepresentative(left, right);
    }

    private int channelPriority(SearchChannelType type) {
        if (type == null) {
            return 99;
        }
        return switch (type) {
            case INTENT_DIRECTED -> 1;
            case VECTOR_GLOBAL -> 2;
        };
    }

    private double relevance(RetrievalMatch match) {
        return match == null || match.getRelevanceScore() == null ? 0.0 : match.getRelevanceScore();
    }
}
