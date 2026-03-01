package org.buaa.rag.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.dao.entity.MessageDO;
import org.buaa.rag.dao.entity.MessageSourceDO;
import org.buaa.rag.dao.mapper.MessageMapper;
import org.buaa.rag.dao.mapper.MessageSourceMapper;
import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.service.ConversationService;
import org.buaa.rag.tool.LlmChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);
    private static final int MAX_HISTORY_SIZE = 60;
    private static final String STREAM_PLACEHOLDER = "__STREAMING__";
    private static final String SUMMARY_PREFIX = "历史会话摘要：";
    private static final String SUMMARY_PROMPT = PromptTemplateLoader.load("conversation-summary.st", """
            你是高校对话摘要助手，请根据历史摘要与新增对话生成更新摘要。
            约束：
            1. 只输出一行摘要，不要编号或解释
            2. 重点保留：已咨询话题、当前状态、用户约束条件
            3. 不要编造具体政策条款或数值
            4. 如果信息不足，保持中性总结
            """);

    private final MessageMapper messageMapper;
    private final MessageSourceMapper sourceRepository;
    private final LlmChat llmChat;
    private final RagProperties ragProperties;
    private final Executor memorySummaryExecutor;

    private final Map<String, String> userSessionMap = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, String>>> sessionHistoryMap = new ConcurrentHashMap<>();
    private final Map<String, SummaryState> sessionSummaryMap = new ConcurrentHashMap<>();
    private final Set<String> summaryRunning = ConcurrentHashMap.newKeySet();

    public ConversationServiceImpl(MessageMapper messageMapper,
                                   MessageSourceMapper sourceRepository,
                                   LlmChat llmChat,
                                   RagProperties ragProperties,
                                   @Qualifier("memorySummaryExecutor") Executor memorySummaryExecutor) {
        this.messageMapper = messageMapper;
        this.sourceRepository = sourceRepository;
        this.llmChat = llmChat;
        this.ragProperties = ragProperties;
        this.memorySummaryExecutor = memorySummaryExecutor;
    }

    @Override
    public String obtainOrCreateSession(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        return userSessionMap.computeIfAbsent(normalizedUserId, key -> {
            String existingSession = loadLatestSessionId(normalizedUserId);
            if (existingSession != null) {
                log.info("复用历史会话 - 用户: {}, 会话ID: {}", normalizedUserId, existingSession);
                return existingSession;
            }

            String newSessionId = UUID.randomUUID().toString();
            log.info("创建新会话 - 用户: {}, 会话ID: {}", normalizedUserId, newSessionId);
            return newSessionId;
        });
    }

    @Override
    public List<Map<String, String>> loadConversationHistory(String sessionId) {
        List<Map<String, String>> history = loadHistoryFromDatabase(sessionId);
        if (history != null) {
            List<Map<String, String>> copied = cloneHistoryList(history);
            sessionHistoryMap.put(sessionId, copied);
            return copied;
        }
        return cloneHistoryList(sessionHistoryMap.getOrDefault(sessionId, new ArrayList<>()));
    }

    @Override
    public List<Map<String, String>> loadConversationContext(String sessionId) {
        List<Map<String, String>> history = loadConversationHistory(sessionId);
        if (history.isEmpty()) {
            return history;
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

        scheduleSummaryIfNeeded(sessionId, oldMessages, memoryConfig);
        String summary = selectSummary(sessionId, oldMessages, memoryConfig);
        if (!StringUtils.hasText(summary)) {
            return recentMessages;
        }

        List<Map<String, String>> context = new ArrayList<>();
        Map<String, String> summaryMessage = new HashMap<>();
        summaryMessage.put("role", "system");
        summaryMessage.put("content", SUMMARY_PREFIX + summary);
        summaryMessage.put("timestamp", getCurrentTimestamp());
        context.add(summaryMessage);
        context.addAll(recentMessages);
        return context;
    }

    @Override
    public Long appendUserMessage(String sessionId,
                                  String userId,
                                  String userMessage) {
        String timestamp = getCurrentTimestamp();
        appendHistoryEntry(sessionId, "user", userMessage, timestamp);
        return persistMessage(sessionId, userId, "user", userMessage);
    }

    @Override
    public Long createAssistantPlaceholder(String sessionId, String userId) {
        String timestamp = getCurrentTimestamp();
        appendHistoryEntry(sessionId, "assistant", STREAM_PLACEHOLDER, timestamp);
        return persistMessage(sessionId, userId, "assistant", STREAM_PLACEHOLDER);
    }

    @Override
    public void completeAssistantMessage(String sessionId,
                                         Long assistantMessageId,
                                         String aiResponse,
                                         List<RetrievalMatch> sources) {
        String response = StringUtils.hasText(aiResponse) ? aiResponse : "（本次回答为空）";
        updateMessageContent(assistantMessageId, response);
        persistSources(assistantMessageId, sources);
        replaceLatestAssistantPlaceholder(sessionId, response);
        scheduleSummaryForCurrentSession(sessionId);
    }

    @Override
    public void failAssistantMessage(String sessionId,
                                     Long assistantMessageId,
                                     String fallbackResponse) {
        completeAssistantMessage(sessionId, assistantMessageId, fallbackResponse, List.of());
    }

    @Override
    public Long appendToHistory(String sessionId,
                                String userId,
                                String userMessage,
                                String aiResponse,
                                List<RetrievalMatch> sources) {
        appendUserMessage(sessionId, userId, userMessage);
        Long assistantMessageId = createAssistantPlaceholder(sessionId, userId);
        completeAssistantMessage(sessionId, assistantMessageId, aiResponse, sources);
        trimHistory(sessionId);

        List<Map<String, String>> history = sessionHistoryMap.getOrDefault(sessionId, List.of());
        log.debug("更新会话历史 - 会话: {}, 总消息数: {}", sessionId, history.size());
        return assistantMessageId;
    }

    private void appendHistoryEntry(String sessionId,
                                    String role,
                                    String content,
                                    String timestamp) {
        List<Map<String, String>> history = sessionHistoryMap.computeIfAbsent(
            sessionId,
            key -> new ArrayList<>()
        );

        Map<String, String> entry = new HashMap<>();
        entry.put("role", role);
        entry.put("content", content);
        entry.put("timestamp", timestamp);
        history.add(entry);

        trimHistory(sessionId);
    }

    private void trimHistory(String sessionId) {
        List<Map<String, String>> history = sessionHistoryMap.get(sessionId);
        if (history == null || history.size() <= MAX_HISTORY_SIZE) {
            return;
        }
        List<Map<String, String>> trimmedHistory = new ArrayList<>(
            history.subList(history.size() - MAX_HISTORY_SIZE, history.size())
        );
        sessionHistoryMap.put(sessionId, trimmedHistory);
    }

    private void replaceLatestAssistantPlaceholder(String sessionId, String finalContent) {
        List<Map<String, String>> history = sessionHistoryMap.get(sessionId);
        if (history == null || history.isEmpty()) {
            return;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, String> entry = history.get(i);
            if (entry == null) {
                continue;
            }
            String role = entry.get("role");
            String content = entry.get("content");
            if ("assistant".equalsIgnoreCase(role) && STREAM_PLACEHOLDER.equals(content)) {
                entry.put("content", finalContent);
                entry.put("timestamp", getCurrentTimestamp());
                return;
            }
        }
    }

    private String getCurrentTimestamp() {
        return LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    private String loadLatestSessionId(String userId) {
        try {
            LambdaQueryWrapper<MessageDO> queryWrapper = Wrappers.lambdaQuery(MessageDO.class)
                .eq(MessageDO::getUserId, userId)
                .orderByDesc(MessageDO::getCreatedAt)
                .last("limit 1");
            MessageDO latest = messageMapper.selectOne(queryWrapper);
            return latest != null ? latest.getSessionId() : null;
        } catch (Exception e) {
            log.debug("读取历史会话失败: {}", e.getMessage());
            return null;
        }
    }

    private List<Map<String, String>> loadHistoryFromDatabase(String sessionId) {
        try {
            LambdaQueryWrapper<MessageDO> queryWrapper = Wrappers.lambdaQuery(MessageDO.class)
                .eq(MessageDO::getSessionId, sessionId)
                .orderByAsc(MessageDO::getCreatedAt)
                .last("limit " + MAX_HISTORY_SIZE);
            List<MessageDO> messageDOS = messageMapper.selectList(queryWrapper);
            if (messageDOS == null || messageDOS.isEmpty()) {
                return null;
            }
            List<Map<String, String>> history = new ArrayList<>();
            for (MessageDO messageDO : messageDOS) {
                if (messageDO == null) {
                    continue;
                }
                String content = messageDO.getContent();
                if (STREAM_PLACEHOLDER.equals(content)) {
                    continue;
                }
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                Map<String, String> entry = new HashMap<>();
                entry.put("role", messageDO.getRole());
                entry.put("content", content);
                entry.put("timestamp", formatTimestamp(messageDO.getCreatedAt()));
                history.add(entry);
            }
            return history;
        } catch (Exception e) {
            log.debug("加载对话历史失败: {}", e.getMessage());
            return null;
        }
    }

    private Long persistMessage(String sessionId, String userId, String role, String content) {
        try {
            MessageDO messageDO = new MessageDO();
            messageDO.setSessionId(sessionId);
            messageDO.setUserId(normalizeUserId(userId));
            messageDO.setRole(role);
            messageDO.setContent(content);
            messageMapper.insert(messageDO);
            return messageDO.getId();
        } catch (Exception e) {
            log.debug("持久化对话失败: {}", e.getMessage());
            return null;
        }
    }

    private void updateMessageContent(Long messageId, String content) {
        if (messageId == null) {
            return;
        }
        MessageDO update = new MessageDO();
        update.setId(messageId);
        update.setContent(content);
        messageMapper.updateById(update);
    }

    private void persistSources(Long messageId, List<RetrievalMatch> sources) {
        if (messageId == null || sources == null || sources.isEmpty()) {
            return;
        }
        try {
            for (RetrievalMatch match : sources) {
                MessageSourceDO source = new MessageSourceDO();
                source.setMessageId(messageId);
                source.setDocumentMd5(match.getFileMd5());
                source.setChunkId(match.getChunkId());
                source.setRelevanceScore(match.getRelevanceScore());
                source.setSourceFileName(match.getSourceFileName());
                sourceRepository.insert(source);
            }
        } catch (Exception e) {
            log.debug("持久化来源失败: {}", e.getMessage());
        }
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return getCurrentTimestamp();
        }
        return timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    private void scheduleSummaryForCurrentSession(String sessionId) {
        List<Map<String, String>> history = sessionHistoryMap.get(sessionId);
        if (history == null || history.isEmpty()) {
            return;
        }
        RagProperties.Memory memoryConfig = ragProperties.getMemory();
        int keepTurns = resolveHistoryKeepTurns(memoryConfig);
        int keepMessages = Math.max(2, keepTurns * 2);
        if (history.size() <= keepMessages) {
            return;
        }
        int splitIndex = Math.max(0, history.size() - keepMessages);
        List<Map<String, String>> oldMessages = cloneHistoryList(history.subList(0, splitIndex));
        scheduleSummaryIfNeeded(sessionId, oldMessages, memoryConfig);
    }

    private void scheduleSummaryIfNeeded(String sessionId,
                                         List<Map<String, String>> oldMessages,
                                         RagProperties.Memory memoryConfig) {
        if (oldMessages == null || oldMessages.isEmpty()) {
            return;
        }
        if (memoryConfig == null || !memoryConfig.isSummaryEnabled()) {
            return;
        }

        int summaryStartTurns = resolveSummaryStartTurns(memoryConfig);
        int triggerMessages = Math.max(2, summaryStartTurns * 2);
        if (oldMessages.size() < triggerMessages) {
            return;
        }

        SummaryState state = sessionSummaryMap.get(sessionId);
        int minDeltaMessages = Math.max(2, memoryConfig.getMinDeltaMessages());
        if (state != null && oldMessages.size() <= state.coveredMessages() + minDeltaMessages) {
            return;
        }
        if (!summaryRunning.add(sessionId)) {
            return;
        }

        List<Map<String, String>> snapshot = cloneHistoryList(oldMessages);
        String previousSummary = state != null ? state.summary() : null;
        int summaryMaxChars = resolveSummaryMaxChars(memoryConfig);
        int summaryMaxTokens = resolveSummaryMaxTokens(memoryConfig);

        memorySummaryExecutor.execute(() -> {
            try {
                String updated = generateSummary(previousSummary, snapshot, summaryMaxChars, summaryMaxTokens);
                if (StringUtils.hasText(updated)) {
                    sessionSummaryMap.put(
                        sessionId,
                        new SummaryState(updated, snapshot.size(), System.currentTimeMillis())
                    );
                }
            } catch (Exception e) {
                log.debug("异步摘要生成失败, sessionId={}, error={}", sessionId, e.getMessage());
            } finally {
                summaryRunning.remove(sessionId);
            }
        });
    }

    private String selectSummary(String sessionId,
                                 List<Map<String, String>> oldMessages,
                                 RagProperties.Memory memoryConfig) {
        SummaryState state = sessionSummaryMap.get(sessionId);
        if (state != null && StringUtils.hasText(state.summary()) && state.coveredMessages() >= oldMessages.size()) {
            return state.summary();
        }
        if (state != null && StringUtils.hasText(state.summary())) {
            return state.summary();
        }

        String fallback = buildRuleBasedSummary(oldMessages, resolveSummaryMaxChars(memoryConfig));
        if (StringUtils.hasText(fallback)) {
            sessionSummaryMap.put(sessionId, new SummaryState(fallback, oldMessages.size(), System.currentTimeMillis()));
        }
        return fallback;
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

    private String normalizeUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            return "anonymous";
        }
        return userId.trim();
    }

    private int resolveHistoryKeepTurns(RagProperties.Memory memoryConfig) {
        if (memoryConfig == null || memoryConfig.getHistoryKeepTurns() <= 0) {
            return 4;
        }
        return memoryConfig.getHistoryKeepTurns();
    }

    private int resolveSummaryStartTurns(RagProperties.Memory memoryConfig) {
        if (memoryConfig == null || memoryConfig.getSummaryStartTurns() <= 0) {
            return 8;
        }
        return Math.max(memoryConfig.getSummaryStartTurns(), resolveHistoryKeepTurns(memoryConfig) + 1);
    }

    private int resolveSummaryMaxChars(RagProperties.Memory memoryConfig) {
        if (memoryConfig == null || memoryConfig.getSummaryMaxChars() <= 0) {
            return 320;
        }
        return Math.max(120, memoryConfig.getSummaryMaxChars());
    }

    private int resolveSummaryMaxTokens(RagProperties.Memory memoryConfig) {
        if (memoryConfig == null || memoryConfig.getSummaryMaxTokens() <= 0) {
            return 180;
        }
        return Math.max(64, memoryConfig.getSummaryMaxTokens());
    }

    private record SummaryState(String summary, int coveredMessages, long updatedAt) {
    }
}
