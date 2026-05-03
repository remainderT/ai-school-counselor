package org.buaa.rag.core.online.memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.dao.entity.MessageDO;
import org.buaa.rag.dao.entity.MessageSummaryDO;
import org.buaa.rag.dao.mapper.MessageMapper;
import org.buaa.rag.dao.mapper.MessageSummaryMapper;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.tool.LlmChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * 会话记忆服务：负责历史上下文加载、摘要压缩和上下文组装。
 *
 * <p>核心设计：
 * <ul>
 *   <li>并行加载历史消息和最新摘要，减少加载延迟</li>
 *   <li>摘要以 XML 标签包裹注入（{@code <conversation-summary>}），LLM 边界感知更清晰</li>
 *   <li>摘要压缩只在 assistant 消息写完后触发，避免每次加载历史都触发 LLM 调用</li>
 *   <li>增量摘要：新摘要合并旧摘要，旧摘要以 assistant 消息传入，明确"以本轮对话为准"</li>
 *   <li>分布式 Redis 锁防止并发重复生成</li>
 * </ul>
 */
@Service
public class ConversationMemoryServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryServiceImpl.class);

    /** 摘要注入时使用的 XML 标签，帮助 LLM 明确区分摘要与正式对话历史 */
    private static final String SUMMARY_XML_OPEN  = "<conversation-summary>";
    private static final String SUMMARY_XML_CLOSE = "</conversation-summary>";

    private static final String SUMMARY_LOCK_PREFIX     = "rag:memory:summary:lock:";
    private static final long   SUMMARY_LOCK_TTL_SECONDS = 300L;
    private static final String STREAM_PLACEHOLDER       = "__STREAMING__";

    private final MessageMapper messageMapper;
    private final MessageSummaryMapper messageSummaryMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final LlmChat llmChat;
    private final RagProperties ragProperties;
    private final Executor memorySummaryExecutor;

    public ConversationMemoryServiceImpl(MessageMapper messageMapper,
                                         MessageSummaryMapper messageSummaryMapper,
                                         StringRedisTemplate stringRedisTemplate,
                                         LlmChat llmChat,
                                         RagProperties ragProperties,
                                         @Qualifier("memorySummaryExecutor") Executor memorySummaryExecutor) {
        this.messageMapper         = messageMapper;
        this.messageSummaryMapper  = messageSummaryMapper;
        this.stringRedisTemplate   = stringRedisTemplate;
        this.llmChat               = llmChat;
        this.ragProperties         = ragProperties;
        this.memorySummaryExecutor = memorySummaryExecutor;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 公开 API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 并行加载历史消息 + 最新摘要，组装上下文列表。
     * 摘要以 {@code <conversation-summary>} XML 标签包裹注入到列表首位（system 消息）。
     *
     * <p><b>注意：</b>本方法不触发摘要压缩，压缩由 {@link #scheduleSummary} 在 assistant 写完后调用。
     */
    public List<Map<String, String>> loadContextParallel(String sessionId, Long userId, int maxHistory) {
        RagProperties.Memory memoryConfig = ragProperties.getMemory();
        int limit = maxHistory > 0 ? maxHistory : memoryConfig.getDefaultMaxHistory();

        // 并行：历史消息 + 最新摘要同时从 DB 加载
        CompletableFuture<List<Map<String, String>>> historyFuture = CompletableFuture.supplyAsync(
            () -> {
                try {
                    return loadMessagesBetween(sessionId, null, null, limit);
                } catch (Exception e) {
                    log.warn("并行加载历史消息失败, sessionId={}, error={}", sessionId, e.getMessage());
                    return List.of();
                }
            },
            memorySummaryExecutor
        );

        CompletableFuture<MessageSummaryDO> summaryFuture = CompletableFuture.supplyAsync(
            () -> {
                try {
                    return loadLatestSummary(sessionId, userId);
                } catch (Exception e) {
                    log.warn("并行加载摘要失败, sessionId={}, error={}", sessionId, e.getMessage());
                    return null;
                }
            },
            memorySummaryExecutor
        );

        try {
            CompletableFuture.allOf(historyFuture, summaryFuture).get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("并行加载历史上下文超时(30s), sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("并行加载历史上下文异常, sessionId={}, error={}", sessionId, e.getMessage());
        }

        List<Map<String, String>> history = historyFuture.getNow(List.of());
        MessageSummaryDO summary          = summaryFuture.getNow(null);

        if (history.isEmpty()) {
            return List.of();
        }

        // 截取最近 keepTurns 轮的消息（短期记忆窗口）
        int keepMessages = Math.max(2, resolveHistoryKeepTurns(memoryConfig) * 2);
        List<Map<String, String>> recentMessages = history.size() <= keepMessages
            ? cloneHistoryList(history)
            : cloneHistoryList(history.subList(history.size() - keepMessages, history.size()));

        // 摘要功能关闭或暂无摘要时，直接返回短期记忆
        if (!memoryConfig.isSummaryEnabled()
                || summary == null
                || !StringUtils.hasText(summary.getContent())) {
            return recentMessages;
        }

        // 将摘要以 XML 标签包裹后注入到 system 消息（让 LLM 清晰识别摘要边界）
        String normalizedSummary = normalizeSummary(summary.getContent(), resolveSummaryMaxChars(memoryConfig));
        if (!StringUtils.hasText(normalizedSummary)) {
            return recentMessages;
        }

        List<Map<String, String>> context = new ArrayList<>(recentMessages.size() + 1);
        context.add(buildSummarySystemMessage(normalizedSummary));
        context.addAll(recentMessages);
        log.debug("注入历史摘要 | sessionId={}, 摘要长度={}, 历史轮数={}",
            sessionId, normalizedSummary.length(), recentMessages.size() / 2);
        return context;
    }

    /**
     * 在 assistant 消息写完后调用，异步触发摘要压缩检查。
     * 只有满足条件（轮数阈值、无并发锁、有新增消息）时才会真正触发 LLM。
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     */
    public void scheduleSummary(String sessionId, Long userId) {
        RagProperties.Memory memoryConfig = ragProperties.getMemory();
        if (memoryConfig == null || !memoryConfig.isSummaryEnabled()) {
            return;
        }
        scheduleSummaryIfNeeded(sessionId, userId, memoryConfig);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 摘要压缩核心逻辑
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 判断是否需要触发摘要压缩，满足条件则提交到 {@code memorySummaryExecutor} 异步执行。
     *
     * <p>触发条件（全部满足）：
     * <ol>
     *   <li>user 消息数 &ge; summaryStartTurns</li>
     *   <li>存在待摘要的消息（[lastSummaryMessageId, cutoffId) 区间不为空）</li>
     *   <li>新增消息数 &ge; minDeltaMessages</li>
     *   <li>成功获取 Redis 分布式锁</li>
     * </ol>
     */
    private void scheduleSummaryIfNeeded(String sessionId,
                                         Long userId,
                                         RagProperties.Memory memoryConfig) {
        int summaryStartTurns = Math.max(
            memoryConfig.getSummaryStartTurns() > 0 ? memoryConfig.getSummaryStartTurns() : 8,
            resolveHistoryKeepTurns(memoryConfig) + 1
        );

        // 1. 检查 user 消息数是否达到触发阈值
        Long count = messageMapper.selectCount(
            Wrappers.lambdaQuery(MessageDO.class)
                .eq(MessageDO::getSessionId, sessionId)
                .eq(MessageDO::getRole, "user")
        );
        long userTurns = count == null ? 0L : count;
        if (userTurns < summaryStartTurns) {
            return;
        }

        // 2. 确定摘要截止 ID（保留最近 keepTurns 轮之前的消息 ID）
        Long cutoffId = resolveSummaryCutoffMessageId(sessionId, resolveHistoryKeepTurns(memoryConfig));
        if (cutoffId == null) {
            return;
        }

        // 3. 对比上次摘要边界，判断是否有新增消息可摘要
        MessageSummaryDO latestSummary = loadLatestSummary(sessionId, userId);
        Long afterId = latestSummary == null ? null : latestSummary.getLastMessageId();
        if (afterId != null && afterId >= cutoffId) {
            // 上次摘要已覆盖到截止点，无新内容需要摘要
            return;
        }

        // 4. 加载待摘要的消息片段
        List<Map<String, String>> snapshot = loadMessagesBetween(sessionId, afterId, cutoffId);
        if (snapshot.isEmpty()) {
            return;
        }
        int minDeltaMessages = Math.max(2, memoryConfig.getMinDeltaMessages());
        if (snapshot.size() < minDeltaMessages) {
            return;
        }

        // 5. 获取分布式锁，防止并发重复压缩
        String lockKey = SUMMARY_LOCK_PREFIX + sessionId;
        if (!tryAcquireSummaryLock(lockKey)) {
            log.debug("摘要压缩已有任务在执行, sessionId={}", sessionId);
            return;
        }

        // 6. 准备参数，提交异步任务
        String previousSummary = latestSummary == null ? null : latestSummary.getContent();
        int summaryMaxChars  = resolveSummaryMaxChars(memoryConfig);
        int summaryMaxTokens = memoryConfig.getSummaryMaxTokens() > 0
            ? Math.max(64, memoryConfig.getSummaryMaxTokens()) : 180;
        Long lastMessageId = resolveLastMessageId(snapshot, afterId);

        log.info("触发异步摘要压缩 | sessionId={}, 待压缩消息数={}, afterId={}, cutoffId={}",
            sessionId, snapshot.size(), afterId, cutoffId);

        memorySummaryExecutor.execute(() -> {
            try {
                String updated = generateSummary(previousSummary, snapshot, summaryMaxChars, summaryMaxTokens);
                if (StringUtils.hasText(updated)) {
                    MessageSummaryDO summaryDO = MessageSummaryDO.builder()
                        .sessionId(sessionId)
                        .userId(userId)
                        .content(updated)
                        .lastMessageId(lastMessageId)
                        .build();
                    messageSummaryMapper.insert(summaryDO);
                    log.info("摘要压缩完成 | sessionId={}, 摘要长度={}", sessionId, updated.length());
                }
            } catch (Exception e) {
                log.warn("异步摘要生成失败, sessionId={}, error={}", sessionId, e.getMessage());
            } finally {
                stringRedisTemplate.delete(lockKey);
            }
        });
    }

    /**
     * 调用 LLM 生成或更新摘要。
     *
     * <p>旧摘要以 assistant 消息传入，并附加"以本轮对话为准"的说明，
     * 避免旧摘要中的错误内容影响新摘要生成。
     *
     * @param previousSummary 上次生成的旧摘要（可为 null）
     * @param messages        待摘要的消息列表（[afterId, cutoffId) 区间）
     * @param summaryMaxChars 摘要最大字符数（用于 Prompt 模板参数）
     * @param summaryMaxTokens LLM 生成最大 token 数
     * @return 新摘要文本，失败时回退到旧摘要
     */
    private String generateSummary(String previousSummary,
                                   List<Map<String, String>> messages,
                                   int summaryMaxChars,
                                   int summaryMaxTokens) {
        // 加载 Prompt 模板并注入 summary_max_chars 参数
        String systemPrompt = buildSummarySystemPrompt(summaryMaxChars);

        // 构建 userPrompt：旧摘要（以 "历史摘要" 标注，告知 LLM 合并去重规则）
        StringBuilder userPrompt = new StringBuilder();
        if (StringUtils.hasText(previousSummary)) {
            userPrompt.append("历史摘要（仅用于合并去重，不得作为事实新增来源；若与本轮对话冲突，以本轮对话为准）：\n")
                .append(previousSummary.trim())
                .append("\n\n");
        }

        // 追加本次待摘要的对话记录
        userPrompt.append("本轮新增对话记录：\n");
        int start = Math.max(0, messages.size() - 30);
        for (int i = start; i < messages.size(); i++) {
            Map<String, String> msg = messages.get(i);
            if (msg == null) {
                continue;
            }
            String role    = msg.getOrDefault("role", "user");
            String content = sanitizeForSummary(msg.get("content"), 200);
            if (!StringUtils.hasText(content)) {
                continue;
            }
            userPrompt.append("assistant".equalsIgnoreCase(role) ? "助手: " : "用户: ")
                .append(content)
                .append("\n");
        }
        userPrompt.append("\n合并以上对话与历史摘要，去重后输出更新摘要。")
            .append("要求：严格≤").append(summaryMaxChars).append("字符；仅一行。");

        String output     = llmChat.generateCompletion(systemPrompt, userPrompt.toString(), summaryMaxTokens);
        String normalized = normalizeSummary(output, summaryMaxChars);
        if (StringUtils.hasText(normalized)) {
            return normalized;
        }
        // LLM 输出无效时降级返回旧摘要
        return normalizeSummary(previousSummary, summaryMaxChars);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────────────────────

    /** 构建摘要 system 消息（XML 包裹，对 LLM 边界感知友好） */
    private Map<String, String> buildSummarySystemMessage(String summaryContent) {
        Map<String, String> msg = new HashMap<>();
        msg.put("role", "system");
        msg.put("content", SUMMARY_XML_OPEN + "\n" + summaryContent + "\n" + SUMMARY_XML_CLOSE);
        return msg;
    }

    /**
     * 加载并渲染摘要 Prompt 模板，注入 summary_max_chars 参数。
     * 如果模板中包含 {summary_max_chars} 占位符则替换，否则追加约束说明。
     */
    private String buildSummarySystemPrompt(int summaryMaxChars) {
        String template = PromptTemplateLoader.load("conversation-summary.st");
        if (template.contains("{summary_max_chars}")) {
            return template.replace("{summary_max_chars}", String.valueOf(summaryMaxChars));
        }
        // 兼容旧模板：直接在末尾追加长度约束
        return template + "\n5. 总长度严格不超过 " + summaryMaxChars + " 个字符。";
    }

    private String normalizeSummary(String text, int maxChars) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxChars - 3)) + "...";
    }

    private String sanitizeForSummary(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String cleaned = text.replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (!StringUtils.hasText(cleaned)) {
            return "";
        }
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, Math.max(1, maxLength - 3)) + "...";
    }

    private int resolveHistoryKeepTurns(RagProperties.Memory memoryConfig) {
        if (memoryConfig == null || memoryConfig.getHistoryKeepTurns() <= 0) {
            return 4;
        }
        return memoryConfig.getHistoryKeepTurns();
    }

    private int resolveSummaryMaxChars(RagProperties.Memory memoryConfig) {
        if (memoryConfig == null || memoryConfig.getSummaryMaxChars() <= 0) {
            return 320;
        }
        return Math.max(120, memoryConfig.getSummaryMaxChars());
    }

    /**
     * 计算摘要截止消息 ID：取最近 keepTurns 条 user 消息中最早那条的 ID，
     * 该 ID 之前的消息都需要被摘要压缩。
     */
    private Long resolveSummaryCutoffMessageId(String sessionId, int keepTurns) {
        List<MessageDO> latestUsers = messageMapper.selectList(
            Wrappers.lambdaQuery(MessageDO.class)
                .eq(MessageDO::getSessionId, sessionId)
                .eq(MessageDO::getRole, "user")
                .orderByDesc(MessageDO::getId)
                .last("limit " + Math.max(1, keepTurns))
        );
        if (latestUsers == null || latestUsers.isEmpty()) {
            return null;
        }
        // 倒序查询，最后一条（index = size-1）是最早的那条
        MessageDO oldestKeptUser = latestUsers.get(latestUsers.size() - 1);
        return oldestKeptUser == null ? null : oldestKeptUser.getId();
    }

    private List<Map<String, String>> loadMessagesBetween(String sessionId, Long afterId, Long beforeId) {
        return loadMessagesBetween(sessionId, afterId, beforeId, -1);
    }

    private List<Map<String, String>> loadMessagesBetween(String sessionId, Long afterId, Long beforeId, int limit) {
        LambdaQueryWrapper<MessageDO> query = Wrappers.lambdaQuery(MessageDO.class)
            .eq(MessageDO::getSessionId, sessionId)
            .in(MessageDO::getRole, "user", "assistant");
        if (afterId != null) {
            query.gt(MessageDO::getId, afterId);
        }
        if (beforeId != null) {
            query.lt(MessageDO::getId, beforeId);
        }
        if (limit > 0) {
            query.last("limit " + limit);
        }
        List<MessageDO> messageDOS = messageMapper.selectList(query.orderByAsc(MessageDO::getId));
        if (messageDOS == null || messageDOS.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> history = new ArrayList<>(messageDOS.size());
        for (MessageDO messageDO : messageDOS) {
            if (messageDO == null || !StringUtils.hasText(messageDO.getContent())) {
                continue;
            }
            if (STREAM_PLACEHOLDER.equals(messageDO.getContent())) {
                continue;
            }
            Map<String, String> entry = new HashMap<>();
            entry.put("role", messageDO.getRole());
            entry.put("content", messageDO.getContent());
            entry.put("id", String.valueOf(messageDO.getId()));
            history.add(entry);
        }
        return history;
    }

    private Long resolveLastMessageId(List<Map<String, String>> snapshot, Long fallback) {
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Map<String, String> item = snapshot.get(i);
            if (item == null) {
                continue;
            }
            String rawId = item.get("id");
            if (!StringUtils.hasText(rawId)) {
                continue;
            }
            try {
                return Long.valueOf(rawId.trim());
            } catch (Exception ignore) {
                // 忽略解析失败，继续往前找
            }
        }
        return fallback;
    }

    private MessageSummaryDO loadLatestSummary(String sessionId, Long userId) {
        LambdaQueryWrapper<MessageSummaryDO> query = Wrappers.lambdaQuery(MessageSummaryDO.class)
            .eq(MessageSummaryDO::getSessionId, sessionId)
            .orderByDesc(MessageSummaryDO::getId)
            .last("limit 1");
        if (userId != null) {
            query.eq(MessageSummaryDO::getUserId, userId);
        }
        return messageSummaryMapper.selectOne(query);
    }

    private boolean tryAcquireSummaryLock(String lockKey) {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                String.valueOf(System.currentTimeMillis()),
                SUMMARY_LOCK_TTL_SECONDS,
                TimeUnit.SECONDS
            );
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.debug("获取摘要锁失败, key={}, error={}", lockKey, e.getMessage());
            return false;
        }
    }

    private List<Map<String, String>> cloneHistoryList(List<Map<String, String>> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, String>> copied = new ArrayList<>(source.size());
        for (Map<String, String> item : source) {
            if (item != null) {
                copied.add(new HashMap<>(item));
            }
        }
        return copied;
    }
}
