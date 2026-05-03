package org.buaa.rag.core.online.retrieval.channel;

import org.buaa.rag.common.enums.SearchChannelType;

/**
 * 可插拔的检索通道：每个实现封装一种独立的召回策略（意图定向、向量全局等）。
 *
 * <p>多通道引擎在运行时通过 {@link #isApplicable(SearchContext)} 判断哪些通道
 * 参与当前轮检索，再按 {@link #dispatchOrder()} 决定调度顺序。通道之间互不感知，
 * 跨通道的融合与去重由后处理链统一完成。
 *
 * <p>实现提示：
 * <ul>
 *   <li>通道应当是无状态的；每次调用 {@link #fetch(SearchContext)} 均为独立事务</li>
 *   <li>耗时阻塞操作应交由引擎提供的线程池异步执行，通道自身不应阻塞调用线程</li>
 * </ul>
 */
public interface SearchChannel {

    /** 通道标识符（需唯一），用于日志追踪与配置引用。 */
    String channelId();

    /** 通道的自然语言说明，可在管理后台展示。 */
    default String description() {
        return channelId();
    }

    /** 调度优先级，值越小越优先被执行。 */
    int dispatchOrder();

    /**
     * 判断当前请求上下文下本通道是否应被纳入检索。
     *
     * @param ctx 包含用户查询、意图判定和检索参数的上下文
     * @return {@code true} 表示通道应参与本轮检索
     */
    boolean isApplicable(SearchContext ctx);

    /**
     * 执行检索并返回结果集。
     *
     * @param ctx 检索上下文
     * @return 通道级别的结果封装，不可为 {@code null}
     */
    SearchChannelResult fetch(SearchContext ctx);

    /** 返回通道的类型枚举，便于后处理链区分来源。 */
    SearchChannelType channelType();
}
