package org.buaa.rag.core.online.retrieval.postprocessor;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.retrieval.channel.SearchChannelResult;
import org.buaa.rag.core.online.retrieval.channel.SearchContext;
import org.buaa.rag.properties.RagProperties;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 低分过滤处理器：丢弃相关度分数低于 {@code min-acceptable-score} 阈值的结果。
 *
 * <p>位于精排之后、截断之前（stage=50），确保进入最终答案生成的文档片段
 * 都具备最低限度的语义相关性，避免 0 分或极低分片段混入上下文干扰 LLM 回答质量。
 *
 * <p>阈值来自 {@code rag.retrieval.min-acceptable-score} 配置，默认 0.25。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreFilterPostProcessor implements SearchResultPostProcessor {

    private final RagProperties ragProperties;

    @Override
    public String label() {
        return "score-filter";
    }

    @Override
    public int stage() {
        return 50;
    }

    @Override
    public List<RetrievalMatch> process(List<RetrievalMatch> candidates,
                                        List<SearchChannelResult> channelOutputs,
                                        SearchContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        double threshold = ragProperties.getRetrieval().getMinAcceptableScore();

        List<RetrievalMatch> filtered = candidates.stream()
                .filter(match -> {
                    Double score = match.getRelevanceScore();
                    return score != null && score >= threshold;
                })
                .toList();

        if (filtered.size() < candidates.size()) {
            log.info("低分过滤: {} → {} 条（阈值={}, 移除 {} 条低分结果）",
                    candidates.size(), filtered.size(), threshold,
                    candidates.size() - filtered.size());
        }

        // 避免把候选全部清空导致可回答问题直接变成 NO_ANSWER。
        // 若所有结果都低于阈值，则保留原始 top-1，让后续 CRAG/生成阶段继续判断。
        if (filtered.isEmpty()) {
            RetrievalMatch fallback = candidates.get(0);
            Double score = fallback.getRelevanceScore();
            log.info("低分过滤保底保留 top1 | query='{}' | score={} | channel={}",
                ctx == null ? "" : ctx.getOriginalQuery(),
                score == null ? "null" : score,
                fallback.getChannelType());
            return List.of(fallback);
        }

        return filtered;
    }
}
