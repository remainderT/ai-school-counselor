package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelResult;
import org.buaa.rag.core.online.retrieval.channel.SearchContext;

/**
 * 多通道检索结果的后处理环节（去重、精排、截断等）。
 * <p>
 * 各实现按 {@link #getOrder()} 升序依次执行，形成责任链。
 */
public interface SearchResultPostProcessor {

    /** 处理器名称，用于日志 */
    String getName();

    /** 执行顺序，数值小的先执行 */
    int getOrder();

    /** 判断是否应参与本轮处理 */
    boolean shouldApply(SearchContext context);

    /** 对合并后的匹配列表做转换/过滤 */
    List<RetrievalMatch> apply(List<RetrievalMatch> matches,
                               List<SearchChannelResult> channelResults,
                               SearchContext context);
}
