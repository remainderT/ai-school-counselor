package org.buaa.rag.core.online.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.core.model.QueryPlan;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 查询分析服务实现
 * 包含查询改写与 HyDE 生成
 */
@Service
public class QueryAnalysisService {

    private static final String DEFAULT_REWRITE_PROMPT = PromptTemplateLoader.load("query-rewrite.st");

    private static final String DEFAULT_HYDE_PROMPT = PromptTemplateLoader.load("query-hyde.st");

    private final LlmChat llmChat;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public QueryAnalysisService(LlmChat llmChat,
                                RagProperties ragProperties) {
        this.llmChat = llmChat;
        this.ragProperties = ragProperties;
        this.objectMapper = new ObjectMapper();
    }

    public QueryPlan createPlan(String userQuery) {
        List<String> rewrites = generateRewrites(userQuery);
        String hydeAnswer = generateHydeAnswer(userQuery);
        return new QueryPlan(userQuery, rewrites, hydeAnswer);
    }

    public String rewriteForRouting(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return userQuery;
        }
        List<String> rewrites = generateRewrites(userQuery);
        if (rewrites == null || rewrites.isEmpty()) {
            return userQuery;
        }
        String first = rewrites.get(0);
        return (first == null || first.isBlank()) ? userQuery : first.trim();
    }

    private List<String> generateRewrites(String userQuery) {
        if (!StringUtils.hasText(userQuery)) {
            return List.of();
        }
        if (!ragProperties.getRewrite().isEnabled()) {
            return List.of();
        }

        String prompt = ragProperties.getRewrite().getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_REWRITE_PROMPT;
        }

        String output = llmChat.generateCompletion(prompt, userQuery, 256);
        int limit = ragProperties.getRewrite().getVariants();
        List<String> structured = parseStructured(output, limit);
        if (!structured.isEmpty()) {
            return structured;
        }
        return normalizeRewrites(output, limit);
    }

    private String generateHydeAnswer(String userQuery) {
        if (!ragProperties.getHyde().isEnabled()) {
            return null;
        }

        String prompt = ragProperties.getHyde().getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_HYDE_PROMPT;
        }

        return llmChat.generateCompletion(
            prompt,
            userQuery,
            ragProperties.getHyde().getMaxTokens()
        );
    }

    private List<String> normalizeRewrites(String output, int limit) {
        if (output == null || output.isBlank()) {
            return List.of();
        }

        String[] lines = output.split("\\r?\\n");
        Set<String> rewrites = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.replaceAll("^[-*\\d.、)]+", "").trim();
            if (!trimmed.isEmpty()) {
                rewrites.add(trimmed);
            }
        }

        List<String> result = new ArrayList<>(rewrites);
        if (limit > 0 && result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }

    private List<String> parseStructured(String output, int limit) {
        if (!StringUtils.hasText(output)) {
            return List.of();
        }
        try {
            String jsonText = extractJson(output);
            JsonNode root = objectMapper.readTree(jsonText);
            JsonNode rewritesNode = root.path("rewrites");
            if (!rewritesNode.isArray()) {
                return List.of();
            }
            Set<String> rewrites = new LinkedHashSet<>();
            for (JsonNode item : rewritesNode) {
                String text = item == null ? null : item.asText();
                if (StringUtils.hasText(text)) {
                    rewrites.add(text.trim());
                }
                if (limit > 0 && rewrites.size() >= limit) {
                    break;
                }
            }
            if (rewrites.isEmpty()) {
                String rewrite = root.path("rewrite").asText("");
                if (StringUtils.hasText(rewrite)) {
                    rewrites.add(rewrite.trim());
                }
            }
            return new ArrayList<>(rewrites);
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
}
