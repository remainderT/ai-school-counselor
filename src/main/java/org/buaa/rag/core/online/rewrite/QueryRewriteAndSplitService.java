package org.buaa.rag.core.online.rewrite;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 查询预处理服务：单次 LLM 调用同时完成「改写」和「拆分」。
 *
 * <p>LLM 返回格式：
 * <pre>
 * {
 *   "rewrite": "改写后的主问题",
 *   "sub_questions": ["子问题1", "子问题2"]
 * }
 * </pre>
 *
 * <p>对比旧架构的两次串行调用（{@code QueryRewriteService} + {@code QueryDecomposer}），
 * 此服务节省一次 LLM 网络往返，同时让改写上下文与拆分逻辑共享同一次推理。
 */
@Slf4j
@Service
public class QueryRewriteAndSplitService {

    private static final String DEFAULT_PROMPT =
            PromptTemplateLoader.load("query-rewrite-and-split.st");

    private final LlmChat llmChat;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public QueryRewriteAndSplitService(LlmChat llmChat, RagProperties ragProperties, ObjectMapper objectMapper) {
        this.llmChat = llmChat;
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
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
        if (!StringUtils.hasText(userQuery)) {
            return fallback(userQuery, 0L);
        }

        RagProperties.QueryPreprocess cfg = ragProperties.getQueryPreprocess();
        if (cfg == null || !cfg.isEnabled()) {
            return fallback(userQuery, 0L);
        }

        long start = System.nanoTime();
        String systemPrompt = DEFAULT_PROMPT;
        // 构建携带历史上下文的 userPrompt（最近 2 轮对话作为前缀）
        String userPrompt = buildUserPromptWithHistory(userQuery, conversationHistory);
        String rawOutput = llmChat.generateCompletion(systemPrompt, userPrompt, 512);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;

        if (!StringUtils.hasText(rawOutput)) {
            log.debug("改写+拆分 LLM 无输出，降级透传原始问题");
            return fallback(userQuery, latencyMs);
        }

        ParsedOutput parsed = parse(rawOutput, Math.max(1, cfg.getMaxSubQuestions()));
        if (parsed == null || !StringUtils.hasText(parsed.rewrite())) {
            log.debug("改写+拆分 JSON 解析失败，降级透传原始问题");
            return fallback(userQuery, latencyMs);
        }

        log.debug("查询改写+拆分完成 | 原问题: {} | 改写: {} | 子问题: {} | 耗时: {}ms",
                userQuery, parsed.rewrite(), parsed.subQuestions(), latencyMs);

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
