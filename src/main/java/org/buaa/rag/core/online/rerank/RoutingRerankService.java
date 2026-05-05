package org.buaa.rag.core.online.rerank;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Rerank 精排服务：委托 {@link DashScopeRerankClient} 完成候选文档精排。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingRerankService {

    private final DashScopeRerankClient rerankClient;

    public List<RetrievalMatch> rerank(String query, List<RetrievalMatch> candidates, int topN) {
        if (candidates == null || candidates.size() <= 1) {
            return candidates;
        }

        try {
            List<RetrievalMatch> result = rerankClient.rerank(query, candidates, topN);
            log.debug("[Rerank] 精排完成, 输入={} 输出={}", candidates.size(), result.size());
            return result;
        } catch (Exception e) {
            log.error("[Rerank] DashScope 精排失败, 直接截断返回. error={}", e.getMessage());
            return candidates.size() > topN && topN > 0
                    ? candidates.subList(0, topN) : candidates;
        }
    }
}
