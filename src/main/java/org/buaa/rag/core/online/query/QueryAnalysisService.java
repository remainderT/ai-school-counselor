package org.buaa.rag.core.online.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.core.model.QueryPlan;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;

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

    public QueryAnalysisService(LlmChat llmChat,
                                RagProperties ragProperties) {
        this.llmChat = llmChat;
        this.ragProperties = ragProperties;
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
        if (!ragProperties.getRewrite().isEnabled()) {
            return List.of();
        }

        String prompt = ragProperties.getRewrite().getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_REWRITE_PROMPT;
        }

        String output = llmChat.generateCompletion(prompt, userQuery, 256);
        return normalizeRewrites(output, ragProperties.getRewrite().getVariants());
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
}
