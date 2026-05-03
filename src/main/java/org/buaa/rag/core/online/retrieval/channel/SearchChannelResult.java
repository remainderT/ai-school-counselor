package org.buaa.rag.core.online.retrieval.channel;

import java.util.List;
import java.util.Map;

import org.buaa.rag.common.enums.SearchChannelType;
import org.buaa.rag.core.model.RetrievalMatch;

/**
 * 单通道检索结果的不可变快照。
 *
 * <p>使用 {@link #of(SearchChannelType, String, List, double, long)}
 * 或 {@link #empty(SearchChannelType, String)} 工厂方法创建实例，
 * 避免在多线程环境下对同一结果对象做修改。
 *
 * @param source       结果来源的通道类型
 * @param channelId    通道标识符
 * @param hits         命中的检索匹配列表（不可变视图）
 * @param topScore     本通道最高匹配分数，0.0 表示无有效结果
 * @param costMs       通道检索耗时（毫秒）
 * @param extra        扩展字段，供特定通道传递诊断信息
 */
public record SearchChannelResult(
        SearchChannelType source,
        String channelId,
        List<RetrievalMatch> hits,
        double topScore,
        long costMs,
        Map<String, Object> extra
) {

    /**
     * 创建包含结果的快照。
     */
    public static SearchChannelResult of(SearchChannelType source,
                                         String channelId,
                                         List<RetrievalMatch> hits,
                                         double topScore,
                                         long costMs) {
        return new SearchChannelResult(
                source,
                channelId,
                hits == null ? List.of() : List.copyOf(hits),
                topScore,
                costMs,
                Map.of()
        );
    }

    /**
     * 创建空结果快照（通道无可用数据或执行失败时使用）。
     */
    public static SearchChannelResult empty(SearchChannelType source, String channelId) {
        return new SearchChannelResult(source, channelId, List.of(), 0.0, 0L, Map.of());
    }

    /** 是否包含有效命中 */
    public boolean hasHits() {
        return hits != null && !hits.isEmpty();
    }

    /** 命中数量 */
    public int hitCount() {
        return hits == null ? 0 : hits.size();
    }
}
