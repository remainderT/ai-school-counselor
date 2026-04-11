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

@Service
public class ConversationMemoryServiceImpl implements ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryServiceImpl.class);
    private static final String SUMMARY_PREFIX = "历史会话摘要：";
    private static final String SUMMARY_LOCK_PREFIX = "rag:memory:summary:lock:";
    private static final long SUMMARY_LOCK_TTL_SECONDS = 300L;
    private static final String STREAM_PLACEHOLDER = "__STREAMING__";
    private static final String SUMMARY_PROMPT = PromptTemplateLoader.load("conversation-summary.st");

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
        this.messageMapper = messageMapper;
        this.messageSummaryMapper = messageSummaryMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.llmChat = llmChat;
        this.ragProperties = ragProperties;
        this.memorySummaryExecutor = memorySummaryExecutor;
    }

    @Override
    public List<Map<String, String>> buildContext(String sessionId, List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return history == null ? List.of() : history;
        }

        RagProperties.Memory memoryConfig = ragProperties.getMemory();
        int keepTurns = resolveHistoryKeepTurns(memoryConfig);
        int keepMessages = Math.max(2, keepTurns * 2);
        if (history.size() <= keepMessages) {
            return history;
        }

        int splitIndex = Math.max(0, history.size() - keepMessages);
        List<Map<String, String>> oldMessages = cloneHistoryList(history.subList(0, splitIndex));
        List<Map<String, String>> recentMessages = cloneHistoryList(history.subList(splitIndex, history.size()));

        if (memoryConfig == null || !memoryConfig.isSummaryEnabled()) {
            return recentMessages;
        }

        Long userId = resolveSessionUserId(sessionId);
        scheduleSummaryIfNeeded(sessionId, userId, memoryConfig);
        String summary = selectSummary(sessionId, userId, oldMessages, memoryConfig);
        if (!StringUtils.hasText(summary)) {
            return recentMessages;
        }

        List<Map<String, String>> context = new ArrayList<>();
        Map<String, String> summaryMessage = new HashMap<>();
        summaryMessage.put("role", "system");
        summaryMessage.put("content", SUMMARY_PREFIX + summary);
        context.add(summaryMessage);
        context.addAll(recentMessages);
        return context;
    }

    @Override
    public List<Map<String, String>> loadContextParallel(String sessionId, Long userId, int maxHistory) {
        RagProperties.Memory memoryConfig = ragProperties.getMemory();
        int limit = maxHistory > 0 ? maxHistory : ragProperties.getMemory().getDefaultMaxHistory();

        // 并行：历史消息 + 最新摘要同时从 DB 加载
        CompletableFuture<List<Map<String, String>>> historyFuture = CompletableFuture.supplyAsync(
            () -> {
                try {
                    return loadMessagesBetween(sessionId, null, null, limit);
                } catch (Exception e) {
                    log.debug("并行加载历史消息失败, sessionId={}, error={}", sessionId, e.getMessage());
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
                    log.debug("并行加载摘要失败, sessionId={}, error={}", sessionId, e.getMessage());
                    return null;
                }
            },
            memorySummaryExecutor
        );

        // 等待两个任务都完成
        CompletableFuture.allOf(historyFuture, summaryFuture).join();

        List<Map<String, String>> history = historyFuture.join();
        MessageSummaryDO summary = summaryFuture.join();

        if (history.isEmpty()) {
            return List.of();
        }

        // 按 buildContext 相同逻辑截取 + 注入摘要
        int keepTurns = resolveHistoryKeepTurns(memoryConfig);
        int keepMessages = Math.max(2, keepTurns * 2);

        List<Map<String, String>> recentMessages = history.size() <= keepMessages
            ? cloneHistoryList(history)
            : cloneHistoryList(history.subList(history.size() - keepMessages, history.size()));

        if (memoryConfig == null || !memoryConfig.isSummaryEnabled()
                || summary == null || !StringUtils.hasText(summary.getContent())) {
            // 摘要加载完之后异步触发压缩检查
            scheduleSummaryIfNeeded(sessionId, userId, memoryConfig);
            return recentMessages;
        }

        // 摘要已加载，直接注入（不再二次查 DB）
        String normalizedSummary = normalizeSummary(summary.getContent(), resolveSummaryMaxChars(memoryConfig));
        scheduleSummaryIfNeeded(sessionId, userId, memoryConfig);

        if (!StringUtils.hasText(normalizedSummary)) {
            return recentMessages;
        }
        List<Map<String, String>> context = new ArrayList<>(recentMessages.size() + 1);
        Map<String, String> summaryMessage = new HashMap<>();
        summaryMessage.put("role", "system");
        summaryMessage.put("content", SUMMARY_PREFIX + normalizedSummary);
        context.add(summaryMessage);
        context.addAll(recentMessages);
        return context;
    }

    @Override
    public void scheduleSummary(String sessionId, Long userId) {
        RagProperties.Memory memoryConfig = ragProperties.getMemory();
        if (memoryConfig == null || !memoryConfig.isSummaryEnabled()) {
            return;
        }
        scheduleSummaryIfNeeded(sessionId, userId, memoryConfig);
    }

    private void scheduleSummaryIfNeeded(String sessionId,
                                         Long userId,
                                         RagProperties.Memory memoryConfig) {
        if (memoryConfig == null || !memoryConfig.isSummaryEnabled()) {
            return;
        }
        int summaryStartTurns = (memoryConfig == null || memoryConfig.getSummaryStartTurns() <= 0)
                ? 8 : Math.max(memoryConfig.getSummaryStartTurns(), resolveHistoryKeepTurns(memoryConfig) + 1);
        Long count = messageMapper.selectCount(
            Wrappers.lambdaQuery(MessageDO.class)
                .eq(MessageDO::getSessionId, sessionId)
                .eq(MessageDO::getRole, "user")
        );
        long userTurns = count == null ? 0 : count;
        if (userTurns < summaryStartTurns) {
            return;
        }
        Long cutoffId = resolveSummaryCutoffMessageId(sessionId, resolveHistoryKeepTurns(memoryConfig));
        if (cutoffId == null) {
            return;
        }
        MessageSummaryDO latestSummary = loadLatestSummary(sessionId, userId);
        Long afterId = latestSummary == null ? null : latestSummary.getLastMessageId();
        if (afterId != null && afterId >= cutoffId) {
            return;
        }
        List<Map<String, String>> snapshot = loadMessagesBetween(sessionId, afterId, cutoffId);
        if (snapshot.isEmpty()) {
            return;
        }
        int minDeltaMessages = Math.max(2, memoryConfig.getMinDeltaMessages());
        if (snapshot.size() < minDeltaMessages) {
            return;
        }
        String lockKey = SUMMARY_LOCK_PREFIX + sessionId;
        if (!tryAcquireSummaryLock(lockKey)) {
            return;
        }
        String previousSummary = latestSummary == null ? null : latestSummary.getContent();
        int summaryMaxChars = resolveSummaryMaxChars(memoryConfig);
        int summaryMaxTokens = (memoryConfig == null || memoryConfig.getSummaryMaxTokens() <= 0)
                ? 180 : Math.max(64, memoryConfig.getSummaryMaxTokens());

        memorySummaryExecutor.execute(() -> {
            try {
                String updated = generateSummary(previousSummary, snapshot, summaryMaxChars, summaryMaxTokens);
                if (StringUtils.hasText(updated)) {
                    MessageSummaryDO summaryDO = MessageSummaryDO.builder()
                        .sessionId(sessionId)
                        .userId(userId)
                        .content(updated)
                        .lastMessageId(resolveLastMessageId(snapshot, afterId))
                        .build();
                    messageSummaryMapper.insert(summaryDO);
                }
            } catch (Exception e) {
                log.debug("异步摘要生成失败, sessionId={}, error={}", sessionId, e.getMessage());
            } finally {
                stringRedisTemplate.delete(lockKey);
            }
        });
    }

    private String selectSummary(String sessionId,
                                 Long userId,
                                 List<Map<String, String>> oldMessages,
                                 RagProperties.Memory memoryConfig) {
        MessageSummaryDO summary = loadLatestSummary(sessionId, userId);
        if (summary != null && StringUtils.hasText(summary.getContent())) {
            return normalizeSummary(summary.getContent(), resolveSummaryMaxChars(memoryConfig));
        }
        return buildRuleBasedSummary(oldMessages, resolveSummaryMaxChars(memoryConfig));
    }

    private String generateSummary(String previousSummary,
                                   List<Map<String, String>> oldMessages,
                                   int summaryMaxChars,
                                   int summaryMaxTokens) {
        StringBuilder userPrompt = new StringBuilder();
        if (StringUtils.hasText(previousSummary)) {
            userPrompt.append("历史摘要：").append(previousSummary.trim()).append("\n\n");
        }
        userPrompt.append("新增对话记录：\n");
        int start = Math.max(0, oldMessages.size() - 30);
        for (int i = start; i < oldMessages.size(); i++) {
            Map<String, String> message = oldMessages.get(i);
            if (message == null) {
                continue;
            }
            String role = message.getOrDefault("role", "user");
            String content = sanitizeForSummary(message.get("content"), 180);
            if (!StringUtils.hasText(content)) {
                continue;
            }
            userPrompt.append("assistant".equalsIgnoreCase(role) ? "助手: " : "用户: ")
                .append(content)
                .append("\n");
        }
        userPrompt.append("\n请输出更新后的摘要。");

        String output = llmChat.generateCompletion(SUMMARY_PROMPT, userPrompt.toString(), summaryMaxTokens);
        String normalized = normalizeSummary(output, summaryMaxChars);
        if (StringUtils.hasText(normalized)) {
            return normalized;
        }
        return normalizeSummary(previousSummary, summaryMaxChars);
    }

    private String buildRuleBasedSummary(List<Map<String, String>> oldMessages, int maxChars) {
        if (oldMessages == null || oldMessages.isEmpty()) {
            return "";
        }
        List<String> topics = new ArrayList<>();
        for (Map<String, String> message : oldMessages) {
            if (message == null) {
                continue;
            }
            String role = message.get("role");
            if (!"user".equalsIgnoreCase(role)) {
                continue;
            }
            String content = sanitizeForSummary(message.get("content"), 40);
            if (StringUtils.hasText(content)) {
                topics.add(content);
            }
        }
        if (topics.isEmpty()) {
            return "";
        }
        int start = Math.max(0, topics.size() - 6);
        String merged = "用户近期主要咨询：" + String.join("；", topics.subList(start, topics.size()));
        return normalizeSummary(merged, maxChars);
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

    private Long resolveSessionUserId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        MessageDO latest = messageMapper.selectOne(
            Wrappers.lambdaQuery(MessageDO.class)
                .eq(MessageDO::getSessionId, sessionId)
                .orderByDesc(MessageDO::getId)
                .last("limit 1")
        );
        return latest == null ? null : latest.getUserId();
    }

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
                // ignore parse failure and keep looking
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
        List<Map<String, String>> copied = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return copied;
        }
        for (Map<String, String> item : source) {
            if (item == null) {
                continue;
            }
            copied.add(new HashMap<>(item));
        }
        return copied;
    }
}
