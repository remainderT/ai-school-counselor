package org.buaa.rag.core.online.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class QueryDecomposer {

    private static final String DEFAULT_PROMPT = PromptTemplateLoader.load("query-decomposer.st");

    private final LlmChat llmChat;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public QueryDecomposer(LlmChat llmChat, RagProperties ragProperties) {
        this.llmChat = llmChat;
        this.ragProperties = ragProperties;
        this.objectMapper = new ObjectMapper();
    }

    public List<String> decompose(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        RagProperties.Decomposition cfg = ragProperties.getDecomposition();
        if (cfg == null || !cfg.isEnabled()) {
            return List.of(query);
        }

        List<String> heuristic = ruleBasedDecompose(query, cfg.getMaxSubqueries());
        if (heuristic.size() > 1) {
            return heuristic;
        }

        String prompt = cfg.getPrompt();
        if (!StringUtils.hasText(prompt)) {
            prompt = DEFAULT_PROMPT;
        }

        String output = llmChat.generateCompletion(prompt, query, 256);
        List<String> parts = parseStructured(output, cfg.getMaxSubqueries());
        if (isValidSplit(parts, query)) {
            return parts;
        }

        // 结构化输出失败时，兜底尝试按旧格式提取列表
        parts = normalizeLines(output, cfg.getMaxSubqueries());
        if (isValidSplit(parts, query)) {
            return parts;
        }
        return List.of(query);
    }

    private List<String> parseStructured(String output, int max) {
        if (!StringUtils.hasText(output)) {
            return List.of();
        }
        try {
            String jsonText = extractJson(output);
            JsonNode root = objectMapper.readTree(jsonText);
            boolean split = root.path("split").asBoolean(false);
            if (!split) {
                return List.of();
            }
            JsonNode subqueriesNode = root.path("subqueries");
            if (!subqueriesNode.isArray()) {
                return List.of();
            }
            Set<String> results = new LinkedHashSet<>();
            for (JsonNode subquery : subqueriesNode) {
                String text = subquery.asText();
                if (StringUtils.hasText(text)) {
                    results.add(text.trim());
                }
                if (results.size() >= max) {
                    break;
                }
            }
            return new ArrayList<>(results);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String extractJson(String output) {
        String trimmed = output.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLineEnd > 0 && lastFence > firstLineEnd) {
                return trimmed.substring(firstLineEnd + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private List<String> normalizeLines(String output, int max) {
        if (!StringUtils.hasText(output)) {
            return List.of();
        }
        String[] lines = output.split("\\r?\\n");
        Set<String> results = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.replaceAll("^[-*\\d.、)]+", "").trim();
            if (StringUtils.hasText(trimmed)) {
                results.add(trimmed);
            }
            if (results.size() >= max) {
                break;
            }
        }
        return new ArrayList<>(results);
    }

    private boolean isValidSplit(List<String> parts, String originalQuery) {
        if (parts == null || parts.size() <= 1) {
            return false;
        }
        String normalizedOriginal = normalize(originalQuery);
        long uniqueCount = parts.stream()
            .filter(StringUtils::hasText)
            .map(this::normalize)
            .filter(StringUtils::hasText)
            .distinct()
            .count();
        if (uniqueCount <= 1) {
            return false;
        }
        if (uniqueCount == 2) {
            boolean allSameAsOriginal = parts.stream()
                .filter(StringUtils::hasText)
                .map(this::normalize)
                .allMatch(item -> item.equals(normalizedOriginal));
            if (allSameAsOriginal) {
                return false;
            }
        }
        return true;
    }

    private String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replaceAll("\\s+", "")
            .replace("，", "")
            .replace("。", "")
            .replace("？", "")
            .replace("?", "")
            .trim();
    }

    private List<String> ruleBasedDecompose(String query, int max) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        boolean hasScholarship = containsAny(query,
            "奖学金", "国奖", "校奖", "励志奖学金", "评优");
        boolean hasAcademicRisk = containsAny(query,
            "挂科", "挂了", "不及格", "补考", "成绩", "绩点", "学分");
        boolean hasImpactIntent = containsAny(query,
            "影响", "是否", "会不会", "能不能", "还能", "可以申请");

        if (!(hasScholarship && hasAcademicRisk && hasImpactIntent)) {
            return List.of();
        }

        Set<String> results = new LinkedHashSet<>();
        if (containsAny(query, "挂科", "挂了", "不及格", "补考")) {
            results.add("挂科或补考记录对学业成绩与资格有哪些影响");
        } else {
            results.add("当前成绩与绩点条件对资格有哪些影响");
        }

        if (containsAny(query, "国奖", "国家奖学金")) {
            results.add("国家奖学金申请条件中对挂科和成绩的要求是什么");
        } else {
            results.add("奖学金申请条件中对挂科和成绩的要求是什么");
        }

        return results.stream().limit(Math.max(1, max)).toList();
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.length == 0) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
