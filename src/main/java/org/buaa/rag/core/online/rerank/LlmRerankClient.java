package org.buaa.rag.core.online.rerank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 基于 LLM 文本生成的 Rerank 客户端（降级方案）。
 * <p>
 * 保留原有 LLM rerank 逻辑：构造 prompt 让 LLM 为候选片段打分，
 * 解析 "id:score" 格式输出。当独立 Rerank 模型不可用时自动降级到此实现。
 */
@Slf4j
@Component
public class LlmRerankClient implements RerankClient {

    private static final String DEFAULT_RERANK_PROMPT = PromptTemplateLoader.load("retrieval-rerank.st");

    private final LlmChat llmChat;
    private final RagProperties ragProperties;

    public LlmRerankClient(LlmChat llmChat, RagProperties ragProperties) {
        this.llmChat = llmChat;
        this.ragProperties = ragProperties;
    }

    @Override
    public String provider() {
        return "llm";
    }

    @Override
    public List<RetrievalMatch> rerank(String query, List<RetrievalMatch> candidates, int topN) {
        if (candidates == null || candidates.size() <= 1) {
            return candidates;
        }

        RagProperties.Rerank config = ragProperties.getRerank();
        int candidateLimit = Math.min(config.getMaxCandidates(), candidates.size());
        List<RetrievalMatch> subset = new ArrayList<>(candidates.subList(0, candidateLimit));

        String prompt = buildRerankPrompt(query, subset, config);
        String output = llmChat.generateCompletion(resolveSystemPrompt(config), prompt, 256);

        Map<Integer, Double> scoreMap = parseScores(output);
        if (scoreMap.isEmpty()) {
            log.debug("[LlmRerank] 重排输出为空，保持原排序");
            return truncate(candidates, topN);
        }

        for (int i = 0; i < subset.size(); i++) {
            Double score = scoreMap.get(i + 1);
            if (score != null) {
                subset.get(i).setRelevanceScore(score);
            }
        }

        subset.sort(Comparator.comparingDouble(
                (RetrievalMatch m) -> m.getRelevanceScore() != null ? m.getRelevanceScore() : 0.0
        ).reversed());

        List<RetrievalMatch> reranked = new ArrayList<>(subset);
        if (candidates.size() > candidateLimit) {
            reranked.addAll(candidates.subList(candidateLimit, candidates.size()));
        }

        return truncate(reranked, topN);
    }

    private String buildRerankPrompt(String query, List<RetrievalMatch> candidates,
                                     RagProperties.Rerank config) {
        int snippetLength = config.getSnippetLength();
        StringBuilder builder = new StringBuilder();
        builder.append("问题：").append(query).append("\n");
        builder.append("候选片段：\n");
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalMatch match = candidates.get(i);
            String snippet = buildSnippet(match.getTextContent(), snippetLength);
            String fileName = match.getSourceFileName() != null ? match.getSourceFileName() : "未知来源";
            builder.append(i + 1).append("|").append(fileName).append("|").append(snippet).append("\n");
        }
        builder.append("请输出评分：\n");
        return builder.toString();
    }

    private String buildSnippet(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "...";
    }

    private String resolveSystemPrompt(RagProperties.Rerank config) {
        return DEFAULT_RERANK_PROMPT;
    }

    private Map<Integer, Double> parseScores(String output) {
        Map<Integer, Double> scores = new HashMap<>();
        if (output == null || output.isBlank()) {
            return scores;
        }
        for (String line : output.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("[:：]");
            if (parts.length < 2) {
                continue;
            }
            try {
                int id = Integer.parseInt(parts[0].trim());
                double score = Double.parseDouble(parts[1].trim());
                score = Math.max(0, Math.min(1, score));
                scores.put(id, score);
            } catch (NumberFormatException ignored) {
                // skip unparseable lines
            }
        }
        return scores;
    }

    private List<RetrievalMatch> truncate(List<RetrievalMatch> list, int topN) {
        if (topN <= 0 || list.size() <= topN) {
            return list;
        }
        return list.subList(0, topN);
    }
}
