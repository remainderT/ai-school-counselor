package org.buaa.rag.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.config.LlmConfiguration;
import org.buaa.rag.config.RagConfiguration;
import org.buaa.rag.dto.CragDecision;
import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.service.RetrievalPostProcessorService;
import org.buaa.rag.tool.LlmChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 检索后处理服务实现
 * 包含检索质量评估与重排
 */
@Service
public class RetrievalPostProcessorServiceImpl implements RetrievalPostProcessorService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPostProcessorServiceImpl.class);

    private static final String DEFAULT_CRAG_PROMPT = PromptTemplateLoader.load("retrieval-crag.st", """
你是检索质量评估器，请根据问题和候选片段判断是否足以回答。
只输出 JSON，字段如下：
{
  "action": "ANSWER|REFINE|CLARIFY|NO_ANSWER",
  "clarifyQuestion": "当需要澄清时给出一句问题"
}
如果问题本身模糊返回 CLARIFY；资料不足返回 REFINE 或 NO_ANSWER。
""");

    private static final String DEFAULT_CLARIFY_PROMPT = PromptTemplateLoader.load("retrieval-clarify.st", """
你是问答助手，请基于用户问题生成一个澄清问题，帮助补充场景信息。
要求：
1. 一句话
2. 不要给出答案
3. 使用简体中文
""");

    private static final String DEFAULT_RERANK_PROMPT = PromptTemplateLoader.load("retrieval-rerank.st", """
你是检索重排助手，请根据用户问题为候选片段打相关度分数。
要求：
1. 评分范围 0.0-1.0，越高越相关
2. 只输出 "id:score" 每行一条
3. 不要输出多余解释或符号
""");

    private final LlmChat llmChat;
    private final RagConfiguration ragConfiguration;
    private final LlmConfiguration llmConfiguration;
    private final ObjectMapper objectMapper;

    public RetrievalPostProcessorServiceImpl(LlmChat llmChat,
                                             RagConfiguration ragConfiguration,
                                             LlmConfiguration llmConfiguration) {
        this.llmChat = llmChat;
        this.ragConfiguration = ragConfiguration;
        this.llmConfiguration = llmConfiguration;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public CragDecision evaluate(String query, List<RetrievalMatch> matches) {
        RagConfiguration.Crag config = ragConfiguration.getCrag();
        if (config == null || !config.isEnabled()) {
            return new CragDecision(CragDecision.Action.ANSWER, null);
        }

        if (matches == null || matches.isEmpty()) {
            if (isLikelyAmbiguous(query)) {
                return new CragDecision(CragDecision.Action.CLARIFY, buildClarifyQuestion(query, config));
            }
            return new CragDecision(CragDecision.Action.NO_ANSWER, noResultMessage());
        }

        if (config.isUseLlm() && shouldReviewWithLlm(matches, config)) {
            CragDecision decision = evaluateWithLlm(query, matches, config);
            if (decision != null) {
                return decision;
            }
        }

        if (isLowQuality(matches, config.getMinScore())) {
            return new CragDecision(CragDecision.Action.REFINE, null);
        }

        return new CragDecision(CragDecision.Action.ANSWER, null);
    }

    @Override
    public String noResultMessage() {
        if (llmConfiguration != null && llmConfiguration.getPromptTemplate() != null) {
            String noResult = llmConfiguration.getPromptTemplate().getNoResultText();
            if (noResult != null && !noResult.isBlank()) {
                return noResult;
            }
        }
        return "暂无相关信息";
    }

    @Override
    public List<RetrievalMatch> rerank(String query, List<RetrievalMatch> matches, int topK) {
        if (matches == null || matches.size() <= 1) {
            return matches;
        }
        RagConfiguration.Rerank config = ragConfiguration.getRerank();
        if (config == null || !config.isEnabled()) {
            return matches;
        }

        int candidateLimit = Math.min(config.getMaxCandidates(), matches.size());
        List<RetrievalMatch> candidates = new ArrayList<>(matches.subList(0, candidateLimit));
        String prompt = buildRerankPrompt(query, candidates, config);

        String output = llmChat.generateCompletion(
            resolveRerankPrompt(config),
            prompt,
            256
        );

        Map<Integer, Double> scoreMap = parseScores(output);
        if (scoreMap.isEmpty()) {
            log.debug("重排输出为空，保持原排序");
            return matches;
        }

        for (int i = 0; i < candidates.size(); i++) {
            RetrievalMatch match = candidates.get(i);
            Double score = scoreMap.get(i + 1);
            if (score != null) {
                match.setRelevanceScore(score);
            }
        }

        candidates.sort(Comparator.comparingDouble(
            (RetrievalMatch match) -> match.getRelevanceScore() != null ? match.getRelevanceScore() : 0.0
        ).reversed());

        List<RetrievalMatch> reranked = new ArrayList<>(candidates);
        if (matches.size() > candidateLimit) {
            reranked.addAll(matches.subList(candidateLimit, matches.size()));
        }

        if (reranked.size() > topK) {
            return reranked.subList(0, topK);
        }
        return reranked;
    }

    private boolean isLowQuality(List<RetrievalMatch> matches, double minScore) {
        if (matches == null || matches.isEmpty()) {
            return true;
        }
        Double score = matches.get(0).getRelevanceScore();
        return score == null || score < minScore;
    }

    private boolean shouldReviewWithLlm(List<RetrievalMatch> matches, RagConfiguration.Crag config) {
        if (matches == null || matches.isEmpty()) {
            return true;
        }
        Double score = matches.get(0).getRelevanceScore();
        if (score == null) {
            return true;
        }
        return score < config.getMinScore() * 1.5;
    }

    private CragDecision evaluateWithLlm(String query,
                                         List<RetrievalMatch> matches,
                                         RagConfiguration.Crag config) {
        String prompt = config.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_CRAG_PROMPT;
        }

        StringBuilder content = new StringBuilder();
        content.append("问题：").append(query).append("\n");
        content.append("候选片段：\n");
        int reviewTopK = Math.min(config.getReviewTopK(), matches.size());
        for (int i = 0; i < reviewTopK; i++) {
            RetrievalMatch match = matches.get(i);
            content.append(i + 1)
                .append("|")
                .append(truncate(match.getTextContent(), 240))
                .append("\n");
        }

        String output = llmChat.generateCompletion(prompt, content.toString(), 256);
        if (output == null || output.isBlank()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(output.trim());
            String actionText = node.path("action").asText("").toUpperCase();
            CragDecision.Action action = parseAction(actionText);
            if (action == null) {
                return null;
            }
            String clarifyQuestion = node.path("clarifyQuestion").asText(null);
            if (action == CragDecision.Action.CLARIFY) {
                if (clarifyQuestion == null || clarifyQuestion.isBlank()) {
                    clarifyQuestion = buildClarifyQuestion(query, config);
                }
                return new CragDecision(action, clarifyQuestion);
            }
            if (action == CragDecision.Action.NO_ANSWER) {
                return new CragDecision(action, noResultMessage());
            }
            return new CragDecision(action, null);
        } catch (Exception e) {
            log.debug("CRAG解析失败: {}", e.getMessage());
            return null;
        }
    }

    private CragDecision.Action parseAction(String actionText) {
        if (actionText == null || actionText.isBlank()) {
            return null;
        }
        try {
            return CragDecision.Action.valueOf(actionText);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String buildClarifyQuestion(String query, RagConfiguration.Crag config) {
        if (!config.isUseLlm()) {
            return "为了更准确回答，请补充问题的具体场景，例如涉及哪一年、学院或制度名称。";
        }
        String prompt = config.getClarifyPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_CLARIFY_PROMPT;
        }
        String output = llmChat.generateCompletion(prompt, query, 64);
        if (output == null || output.isBlank()) {
            return "为了更准确回答，请补充问题的具体场景，例如涉及哪一年、学院或制度名称。";
        }
        return output.trim();
    }

    private boolean isLikelyAmbiguous(String message) {
        if (message == null) {
            return true;
        }
        String trimmed = message.trim();
        if (trimmed.length() < 6) {
            return true;
        }
        return trimmed.contains("这个") || trimmed.contains("那个")
            || trimmed.contains("之前") || trimmed.contains("上面")
            || trimmed.contains("怎么弄") || trimmed.contains("怎么办");
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private String buildRerankPrompt(String query,
                                     List<RetrievalMatch> candidates,
                                     RagConfiguration.Rerank config) {
        StringBuilder builder = new StringBuilder();
        builder.append("问题：").append(query).append("\n");
        builder.append("候选片段：\n");
        int snippetLength = config.getSnippetLength();
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalMatch match = candidates.get(i);
            String snippet = buildSnippet(match.getTextContent(), snippetLength);
            String fileName = match.getSourceFileName() != null ? match.getSourceFileName() : "未知来源";
            builder.append(i + 1)
                .append("|")
                .append(fileName)
                .append("|")
                .append(snippet)
                .append("\n");
        }
        builder.append("请输出评分：\n");
        return builder.toString();
    }

    private String buildSnippet(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replaceAll("\\s+", " ").trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...";
    }

    private String resolveRerankPrompt(RagConfiguration.Rerank config) {
        if (config.getPrompt() != null && !config.getPrompt().isBlank()) {
            return config.getPrompt();
        }
        return DEFAULT_RERANK_PROMPT;
    }

    private Map<Integer, Double> parseScores(String output) {
        Map<Integer, Double> scores = new HashMap<>();
        if (output == null || output.isBlank()) {
            return scores;
        }
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
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
                if (score < 0) {
                    score = 0;
                } else if (score > 1) {
                    score = 1;
                }
                scores.put(id, score);
            } catch (NumberFormatException ignored) {
                String normalized = trimmed.toLowerCase(Locale.ROOT);
                if (normalized.contains("score")) {
                    continue;
                }
            }
        }
        return scores;
    }
}
