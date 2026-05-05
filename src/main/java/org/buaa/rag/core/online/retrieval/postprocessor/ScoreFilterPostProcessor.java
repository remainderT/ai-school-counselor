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

        return filtered;
    }
}
