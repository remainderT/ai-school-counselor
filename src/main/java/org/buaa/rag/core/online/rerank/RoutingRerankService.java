package org.buaa.rag.core.online.rerank;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.properties.RagProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 路由式 Rerank 服务：按配置的提供商优先级依次尝试，失败自动降级到下一个。
 * <p>
 * 降级链：dashscope (独立 Rerank 模型) → llm (LLM 文本打分) → noop (直接截断)。
 * <p>
 * 标记 {@code @Primary} 使其成为 {@link RerankService} 的默认实现，
 * 替代原有 {@link org.buaa.rag.core.online.retrieval.postprocessor.RetrievalPostProcessorServiceImpl#rerank} 中的 LLM rerank。
 */
@Slf4j
@Service
@Primary
public class RoutingRerankService implements RerankService {

    private final Map<String, RerankClient> clientsByProvider;
    private final List<String> providerOrder;

    public RoutingRerankService(List<RerankClient> clients, RagProperties ragProperties) {
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(RerankClient::provider, Function.identity()));

        // 从配置读取提供商优先级；未配置时使用默认降级链
        List<String> configured = ragProperties.getRerank().getProviderOrder();
        if (configured != null && !configured.isEmpty()) {
            this.providerOrder = configured;
        } else {
            this.providerOrder = List.of("dashscope", "llm", "noop");
        }

        log.info("[RoutingRerank] 初始化完成, 可用提供商={}, 优先级={}",
                clientsByProvider.keySet(), providerOrder);
    }

    @Override
    public List<RetrievalMatch> rerank(String query, List<RetrievalMatch> candidates, int topN) {
        if (candidates == null || candidates.size() <= 1) {
            return candidates;
        }

        Throwable lastError = null;
        for (String provider : providerOrder) {
            RerankClient client = clientsByProvider.get(provider);
            if (client == null) {
                continue;
            }

            try {
                List<RetrievalMatch> result = client.rerank(query, candidates, topN);
                if (log.isDebugEnabled()) {
                    log.debug("[RoutingRerank] 使用 provider={} 完成 rerank, 输入={} 输出={}",
                            provider, candidates.size(), result.size());
                }
                return result;
            } catch (Exception e) {
                lastError = e;
                log.warn("[RoutingRerank] provider={} 失败, 降级到下一个. error={}",
                        provider, e.getMessage());
            }
        }

        // 所有提供商都失败，直接截断返回
        log.error("[RoutingRerank] 所有提供商均失败, 直接截断返回. lastError={}",
                lastError != null ? lastError.getMessage() : "unknown");
        return candidates.size() > topN && topN > 0
                ? candidates.subList(0, topN) : candidates;
    }
}
