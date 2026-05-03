package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelResult;
import org.buaa.rag.core.online.retrieval.channel.SearchContext;

/**
 * 检索结果后处理环节。
 *
 * <p>多通道合并后的原始匹配列表依次流经所有激活的后处理器（按 {@link #stage()} 升序），
 * 每个处理器可对列表做过滤、去重、重排序或截断。后处理器之间形成职责链，
 * 前序处理器的输出即为后序处理器的输入。
 *
 * <p>设计约定：
 * <ul>
 *   <li>处理器应为无副作用的纯函数——不修改传入列表，而是返回新列表</li>
 *   <li>stage 值建议分段：1–9 去重 / 10–49 精排 / 50–99 增强 / 100+ 截断</li>
 * </ul>
 */
public interface SearchResultPostProcessor {

    /** 处理器标识名，用于日志输出和配置匹配。 */
    String label();

    /**
     * 处理阶段序号，决定在链中的执行顺序。值越小越先执行。
     * <p>推荐分段：去重(1–9)、精排(10–49)、增强(50–99)、截断(100+)。
     */
    int stage();

    /**
     * 是否在当前请求上下文中激活本处理器。
     * <p>默认始终激活；子类可覆写以根据配置或上下文条件跳过。
     */
    default boolean isActive(SearchContext ctx) {
        return true;
    }

    /**
     * 对匹配列表执行处理并返回新的列表。
     *
     * @param candidates     待处理的匹配列表（不应被修改）
     * @param channelOutputs 各通道的原始输出（用于跨通道感知，如去重时判断来源优先级）
     * @param ctx            检索上下文
     * @return 处理后的匹配列表
     */
    List<RetrievalMatch> process(List<RetrievalMatch> candidates,
                                 List<SearchChannelResult> channelOutputs,
                                 SearchContext ctx);
}
