package org.buaa.rag.core.online.rewrite;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.core.online.trace.RagTraceNode;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 查询预处理服务：单次 LLM 调用同时完成「改写」和「拆分」。
 */
@Slf4j
@Service
public class QueryRewriteAndSplitService {

    private static final String DEFAULT_PROMPT =
            PromptTemplateLoader.load("query-rewrite-and-split.st");
    private static final double REWRITE_TEMPERATURE = 0.1D;
    private static final double REWRITE_TOP_P = 0.3D;

    private final LlmChat llmChat;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final QueryTermMappingService queryTermMappingService;

    public QueryRewriteAndSplitService(LlmChat llmChat,
                                       RagProperties ragProperties,
                                       ObjectMapper objectMapper,
                                       QueryTermMappingService queryTermMappingService) {
        this.llmChat = llmChat;
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
        this.queryTermMappingService = queryTermMappingService;
    }

    /**
     * 对用户原始问题做改写 + 拆分（不含历史，兼容旧调用方）。
     */
    public QueryRewriteResult rewriteAndSplit(String userQuery) {
        return rewriteAndSplit(userQuery, null);
    }

    /**
     * 对用户原始问题做改写 + 拆分，携带最近 N 轮对话历史做指代消歧。
     *
     * <p>历史只保留最近 4 条消息（2 轮），过滤 system 摘要消息，避免 token 浪费。
     * 若功能开关关闭或 LLM 调用失败，透传原始问题作为兜底。
     *
     * @param userQuery       当前用户问题
     * @param conversationHistory 完整对话历史（{@code role}/{@code content} Map 列表），可为 null
     */
    public QueryRewriteResult rewriteAndSplit(String userQuery, List<Map<String, String>> conversationHistory) {
        return rewriteWithSplit(userQuery, conversationHistory);
    }

    @RagTraceNode(name = "query-rewrite-and-split", type = "REWRITE")
    public QueryRewriteResult rewriteWithSplit(String userQuery, List<Map<String, String>> conversationHistory) {
        if (!StringUtils.hasText(userQuery)) {
            return fallback(userQuery, 0L);
        }

        RagProperties.QueryPreprocess cfg = ragProperties.getQueryPreprocess();
        if (cfg == null || !cfg.isEnabled()) {
            return fastPathFallback(userQuery, 0L);
        }

        // 词项归一化：在 LLM 改写前先做同义词映射
        String normalizedQuery = queryTermMappingService.normalize(normalizeQuery(userQuery));
        if (shouldUseFastPath(normalizedQuery, conversationHistory)) {
            long start = System.nanoTime();
            List<String> subQuestions = ruleBasedSplit(normalizedQuery, Math.max(1, cfg.getMaxSubQuestions()));
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            log.info("查询改写拆分快速路径命中 | 原问题长度={} | 子问题数={} | 耗时={}ms",
                normalizedQuery.length(), subQuestions.size(), latencyMs);
            return new QueryRewriteResult(normalizedQuery, subQuestions, latencyMs);
        }

        long start = System.nanoTime();
        String systemPrompt = DEFAULT_PROMPT;
        // 构建携带历史上下文的 userPrompt（最近 2 轮对话作为前缀）
        String userPrompt = buildUserPromptWithHistory(normalizedQuery, conversationHistory);
        String rawOutput = llmChat.generateCompletion(
            systemPrompt,
            userPrompt,
            512,
            REWRITE_TEMPERATURE,
            REWRITE_TOP_P
        );
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;

        if (!StringUtils.hasText(rawOutput)) {
            log.debug("改写+拆分 LLM 无输出，降级透传原始问题");
            return fastPathFallback(normalizedQuery, latencyMs);
        }

        ParsedOutput parsed = parse(rawOutput, Math.max(1, cfg.getMaxSubQuestions()));
        if (parsed == null || !StringUtils.hasText(parsed.rewrite())) {
            log.debug("改写+拆分 JSON 解析失败，降级透传原始问题");
            return fastPathFallback(normalizedQuery, latencyMs);
        }

        log.debug("查询改写+拆分完成 | 原问题: {} | 改写: {} | 子问题: {} | 耗时: {}ms",
                normalizedQuery, parsed.rewrite(), parsed.subQuestions(), latencyMs);
        log.info("查询改写拆分完成 | 原问题长度={} | 子问题数={} | 耗时={}ms",
            normalizedQuery.length(), parsed.subQuestions().size(), latencyMs);

        return new QueryRewriteResult(parsed.rewrite(), parsed.subQuestions(), latencyMs);
    }

    // ──────────────────────── private ────────────────────────

    /**
     * 将最近 2 轮（4 条）对话历史拼接到 userPrompt 前，帮助 LLM 做指代消歧。
     * 过滤 system 消息（摘要等），只保留 user/assistant。
     */
    private String buildUserPromptWithHistory(String userQuery,
                                              List<Map<String, String>> conversationHistory) {
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return userQuery;
        }
        // 只保留 user/assistant，跳过 system 摘要
        List<Map<String, String>> relevant = conversationHistory.stream()
            .filter(m -> m != null)
            .filter(m -> {
                String role = m.get("role");
                return "user".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(role);
            })
            .toList();
        if (relevant.isEmpty()) {
            return userQuery;
        }
        // 只取最近 4 条（2 轮对话）
        int fromIdx = Math.max(0, relevant.size() - 4);
        List<Map<String, String>> recent = relevant.subList(fromIdx, relevant.size());

        StringBuilder sb = new StringBuilder();
        sb.append("【对话历史（最近2轮，用于指代消歧）】\n");
        for (Map<String, String> msg : recent) {
            String role = msg.get("role");
            String content = msg.get("content");
            if (!StringUtils.hasText(content)) {
                continue;
            }
            String label = "assistant".equalsIgnoreCase(role) ? "助手" : "用户";
            sb.append(label).append(": ").append(content.trim()).append("\n");
        }
        sb.append("【当前问题】\n").append(userQuery);
        return sb.toString();
    }

    private ParsedOutput parse(String rawOutput, int maxSubQuestions) {
        try {
            String json = stripMarkdownFence(rawOutput);
            JsonNode root = objectMapper.readTree(json);

            String rewrite = root.path("rewrite").asText("").trim();
            if (!StringUtils.hasText(rewrite)) {
                return null;
            }

            List<String> subQuestions = new ArrayList<>();
            JsonNode subNode = root.path("sub_questions");
            if (subNode.isArray()) {
                Set<String> seen = new LinkedHashSet<>();
                for (JsonNode item : subNode) {
                    String q = item.asText("").trim();
                    if (StringUtils.hasText(q) && seen.add(q)) {
                        subQuestions.add(q);
                        if (subQuestions.size() >= maxSubQuestions) {
                            break;
                        }
                    }
                }
            }

            // 过滤掉与 rewrite 完全重复的子问题
            subQuestions.removeIf(q -> q.equals(rewrite));

            return new ParsedOutput(rewrite, subQuestions);
        } catch (Exception e) {
            log.debug("改写+拆分 JSON 解析异常: {}", e.getMessage());
            return null;
        }
    }

    private QueryRewriteResult fallback(String userQuery, long latencyMs) {
        String safe = StringUtils.hasText(userQuery) ? userQuery.trim() : "";
        return new QueryRewriteResult(safe, List.of(), latencyMs);
    }

    private QueryRewriteResult fastPathFallback(String userQuery, long latencyMs) {
        String safe = normalizeQuery(userQuery);
        return new QueryRewriteResult(safe, ruleBasedSplit(safe, Math.max(1, ragProperties.getQueryPreprocess().getMaxSubQuestions())), latencyMs);
    }

    private String normalizeQuery(String userQuery) {
        if (!StringUtils.hasText(userQuery)) {
            return "";
        }
        return userQuery.replaceAll("\\s+", " ").trim();
    }

    private boolean shouldUseFastPath(String query, List<Map<String, String>> conversationHistory) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            return false;
        }
        boolean hasMultiIntentDelimiter = query.contains("；")
            || query.contains(";")
            || query.contains("\n")
            || query.contains("另外")
            || query.contains("还有")
            || query.contains("最后");
        if (!hasMultiIntentDelimiter) {
            return false;
        }
        return !query.contains("它")
            && !query.contains("那个")
            && !query.contains("上面")
            && !query.contains("前面");
    }

    private List<String> ruleBasedSplit(String query, int maxSubQuestions) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String part : query.split("[；;\\n]+")) {
            String normalized = part == null ? "" : part.trim();
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            seen.add(normalized);
            if (seen.size() >= maxSubQuestions) {
                break;
            }
        }
        if (seen.isEmpty()) {
            return List.of(query);
        }
        return List.copyOf(seen);
    }

    /** 去除 LLM 可能输出的 ```json ... ``` 包裹 */
    private static String stripMarkdownFence(String output) {
        String s = output.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return s.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return s;
    }

    private record ParsedOutput(String rewrite, List<String> subQuestions) {
    }
}
