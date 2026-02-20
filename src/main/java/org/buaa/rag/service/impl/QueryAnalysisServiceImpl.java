package org.buaa.rag.service.impl;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.config.RagConfiguration;
import org.buaa.rag.dto.QueryPlan;
import org.buaa.rag.service.QueryAnalysisService;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询分析服务实现
 * 包含查询改写与 HyDE 生成
 */
@Service
public class QueryAnalysisServiceImpl implements QueryAnalysisService {

    private static final String DEFAULT_REWRITE_PROMPT = PromptTemplateLoader.load("query-rewrite.st", """
你是检索查询改写助手，请根据用户问题生成多条可用于检索的改写：
1. 每行只输出一条改写
2. 不要编号或引号
3. 保持简洁，避免增加新事实
""");

    private static final String DEFAULT_HYDE_PROMPT = PromptTemplateLoader.load("query-hyde.st", """
请根据用户问题生成一个可能的理想答案，用于检索。
要求：
1. 使用简体中文
2. 80-150字
3. 不要编造具体数值或机构名称
""");

    private final LlmChat llmChat;
    private final RagConfiguration ragConfiguration;

    public QueryAnalysisServiceImpl(LlmChat llmChat,
                                    RagConfiguration ragConfiguration) {
        this.llmChat = llmChat;
        this.ragConfiguration = ragConfiguration;
    }

    @Override
    public QueryPlan createPlan(String userQuery) {
        List<String> rewrites = generateRewrites(userQuery);
        String hydeAnswer = generateHydeAnswer(userQuery);
        return new QueryPlan(userQuery, rewrites, hydeAnswer);
    }

    private List<String> generateRewrites(String userQuery) {
        if (!ragConfiguration.getRewrite().isEnabled()) {
            return List.of();
        }

        String prompt = ragConfiguration.getRewrite().getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_REWRITE_PROMPT;
        }

        String output = llmChat.generateCompletion(prompt, userQuery, 256);
        return normalizeRewrites(output, ragConfiguration.getRewrite().getVariants());
    }

    private String generateHydeAnswer(String userQuery) {
        if (!ragConfiguration.getHyde().isEnabled()) {
            return null;
        }

        String prompt = ragConfiguration.getHyde().getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_HYDE_PROMPT;
        }

        return llmChat.generateCompletion(
            prompt,
            userQuery,
            ragConfiguration.getHyde().getMaxTokens()
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
