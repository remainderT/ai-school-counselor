package org.buaa.rag.core.online.retrieval.channel;

/**
 * 检索通道抽象，每种通道代表一种独立的召回策略（如向量全局、意图定向等）。
 * <p>
 * 实现类通过 {@link #canActivate(SearchContext)} 决定是否参与本轮检索，
 * 通过 {@link #execute(SearchContext)} 执行具体召回逻辑。
 */
public interface SearchChannel {

    /** 通道可读名称，用于日志和诊断 */
    String getName();

    /** 执行优先级，数字越小越先执行 */
    int getPriority();

    /** 判断当前上下文下通道是否应该激活 */
    boolean canActivate(SearchContext context);

    /** 执行召回并返回结果 */
    SearchChannelResult execute(SearchContext context);

    /** 通道类别标识 */
    SearchChannelType getType();
}
