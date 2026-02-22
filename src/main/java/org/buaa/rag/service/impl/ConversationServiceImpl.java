package org.buaa.rag.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.buaa.rag.dao.entity.MessageDO;
import org.buaa.rag.dao.entity.MessageSourceDO;
import org.buaa.rag.dao.mapper.MessageMapper;
import org.buaa.rag.dao.mapper.MessageSourceMapper;
import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.service.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);
    private static final int MAX_HISTORY_SIZE = 20;
    private static final String STREAM_PLACEHOLDER = "__STREAMING__";

    private final MessageMapper messageMapper;
    private final MessageSourceMapper sourceRepository;

    private final Map<String, String> userSessionMap = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, String>>> sessionHistoryMap = new ConcurrentHashMap<>();

    public ConversationServiceImpl(MessageMapper messageMapper,
                                   MessageSourceMapper sourceRepository) {
        this.messageMapper = messageMapper;
        this.sourceRepository = sourceRepository;
    }

    @Override
    public String obtainOrCreateSession(String userId) {
        return userSessionMap.computeIfAbsent(userId, key -> {
            String existingSession = loadLatestSessionId(userId);
            if (existingSession != null) {
                log.info("复用历史会话 - 用户: {}, 会话ID: {}", userId, existingSession);
                return existingSession;
            }

            String newSessionId = UUID.randomUUID().toString();
            log.info("创建新会话 - 用户: {}, 会话ID: {}", userId, newSessionId);
            return newSessionId;
        });
    }

    @Override
    public List<Map<String, String>> loadConversationHistory(String sessionId) {
        List<Map<String, String>> history = loadHistoryFromDatabase(sessionId);
        if (history != null) {
            sessionHistoryMap.put(sessionId, history);
            return history;
        }
        return sessionHistoryMap.getOrDefault(sessionId, new ArrayList<>());
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
                .last("limit 20");
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
            messageDO.setUserId(userId);
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
}
