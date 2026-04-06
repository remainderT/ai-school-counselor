package org.buaa.rag.core.online.rerank;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;
import org.springframework.stereotype.Component;

/**
 * 空操作 Rerank 客户端：不做任何重排，直接截断到 topN。
 * <p>
 * 作为所有 Rerank 提供商都失败时的最终兜底，确保链路不中断。
 */
@Component
public class NoopRerankClient implements RerankClient {

    @Override
    public String provider() {
        return "noop";
    }

    @Override
    public List<RetrievalMatch> rerank(String query, List<RetrievalMatch> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (topN <= 0 || candidates.size() <= topN) {
            return candidates;
        }
        return candidates.subList(0, topN);
    }
}
