package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 *   <li>每组内按「通道可信度权重 × 匹配分数」的综合评分降序排列</li>
 *   <li>保留每组评分最高的一条记录作为最终代表</li>
 * </ol>
 *
 * <p>与简单的 "保留最高分" 策略不同，本实现引入了通道可信度权重因子，
 * 使得高优先级通道（如意图定向）在分数接近时天然胜出，
 * 避免低优先级通道的噪声结果意外替换精准结果。
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
        return 1;
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

        // 构建通道来源 → 权重映射，用于加权评分
        Map<SearchChannelType, Double> channelWeights = buildChannelWeightMap(channelOutputs);

        // 按 matchKey 分组，每组选综合评分最高的
        int beforeCount = candidates.size();
        Collection<RetrievalMatch> deduplicated = candidates.stream()
                .collect(Collectors.toMap(
                        RetrievalMatch::matchKey,
                        match -> match,
                        (existing, incoming) ->
                                weightedScore(incoming, channelWeights) > weightedScore(existing, channelWeights)
                                        ? incoming : existing,
                        java.util.LinkedHashMap::new
                ))
                .values();

        // 按综合评分降序排列以保持最优顺序
        List<RetrievalMatch> result = deduplicated.stream()
                .sorted(Comparator.comparingDouble(
                        (RetrievalMatch m) -> weightedScore(m, channelWeights)).reversed())
                .toList();

        if (result.size() < beforeCount) {
            log.debug("跨通道去重: {} → {} 条（移除 {} 条重复）",
                    beforeCount, result.size(), beforeCount - result.size());
        }
        return result;
    }

    /**
     * 综合评分 = 匹配原始分数 × 通道可信度权重。
     * <p>权重值越大表示通道越可信，定向通道 > 全局通道。
     */
    private double weightedScore(RetrievalMatch match, Map<SearchChannelType, Double> weights) {
        double rawScore = match.getRelevanceScore() != null ? match.getRelevanceScore() : 0.0;
        SearchChannelType source = match.getChannelType();
        double weight = (source != null && weights.containsKey(source))
                ? weights.get(source) : 1.0;
        return rawScore * weight;
    }

    /**
     * 从通道输出中构建权重表。意图定向通道获得更高的可信度因子。
     */
    private Map<SearchChannelType, Double> buildChannelWeightMap(List<SearchChannelResult> outputs) {
        // 意图定向通道可信度最高，全局检索次之
        return Map.of(
                SearchChannelType.INTENT_DIRECTED, 1.2,
                SearchChannelType.VECTOR_GLOBAL, 1.0
        );
    }
}
