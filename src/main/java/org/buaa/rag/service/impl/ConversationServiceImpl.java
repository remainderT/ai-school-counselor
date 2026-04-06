package org.buaa.rag.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.dao.entity.MessageDO;
import org.buaa.rag.dao.entity.ChatTraceMetricDO;
import org.buaa.rag.dao.entity.MessageSourceDO;
import org.buaa.rag.dao.entity.MessageFeedbackDO;
import org.buaa.rag.dao.entity.MessageSummaryDO;
import org.buaa.rag.dao.mapper.MessageMapper;
import org.buaa.rag.dao.mapper.MessageSourceMapper;
import org.buaa.rag.dao.mapper.MessageFeedbackMapper;
import org.buaa.rag.dao.mapper.ChatTraceMetricMapper;
import org.buaa.rag.dao.mapper.MessageSummaryMapper;
import org.buaa.rag.core.online.memory.ConversationMemoryService;
import org.buaa.rag.dto.resp.ConversationMessageRespDTO;
import org.buaa.rag.dto.resp.ConversationSessionRespDTO;
import org.buaa.rag.service.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);
    private static final int MAX_HISTORY_SIZE = 60;
    private static final String STREAM_PLACEHOLDER = "__STREAMING__";
    private static final String SESSION_MARKER = "__SESSION_MARKER__";
    private static final String SESSION_META_PREFIX = "__SESSION_META__:";

    private final MessageMapper messageMapper;
    private final MessageSourceMapper sourceRepository;
    private final MessageFeedbackMapper messageFeedbackMapper;
    private final ChatTraceMetricMapper chatTraceMetricMapper;
    private final MessageSummaryMapper messageSummaryMapper;
    private final ConversationMemoryService conversationMemoryService;

    private final Map<Long, String> userSessionMap = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, String>>> sessionHistoryMap = new ConcurrentHashMap<>();

    public ConversationServiceImpl(MessageMapper messageMapper,
                                   MessageSourceMapper sourceRepository,
                                   MessageFeedbackMapper messageFeedbackMapper,
                                   ChatTraceMetricMapper chatTraceMetricMapper,
                                   MessageSummaryMapper messageSummaryMapper,
                                   ConversationMemoryService conversationMemoryService) {
        this.messageMapper = messageMapper;
        this.sourceRepository = sourceRepository;
        this.messageFeedbackMapper = messageFeedbackMapper;
        this.chatTraceMetricMapper = chatTraceMetricMapper;
        this.messageSummaryMapper = messageSummaryMapper;
        this.conversationMemoryService = conversationMemoryService;
    }

    @Override
    public String obtainOrCreateSession(Long userId) {
        Long resolvedUserId = resolveUserId(userId);
        return userSessionMap.computeIfAbsent(resolvedUserId, key -> {
            String existingSession = loadLatestSessionId(resolvedUserId);
            if (existingSession != null) {
                log.info("复用历史会话 - 用户: {}, 会话ID: {}", resolvedUserId, existingSession);
                return existingSession;
            }
            String newSessionId = UUID.randomUUID().toString();
            log.info("创建新会话 - 用户: {}, 会话ID: {}", resolvedUserId, newSessionId);
            return newSessionId;
        });
    }

    @Override
    public ConversationSessionRespDTO createSession(Long userId, String title) {
        Long resolvedUserId = resolveUserId(userId);
        String sessionId = UUID.randomUUID().toString();
        String normalizedTitle = normalizeSessionTitle(title);
        persistMessage(sessionId, resolvedUserId, "system", buildSessionMetaContent(normalizedTitle));
        userSessionMap.put(resolvedUserId, sessionId);

        ConversationSessionRespDTO dto = new ConversationSessionRespDTO();
        dto.setSessionId(sessionId);
        dto.setUserId(resolvedUserId);
        dto.setTitle(normalizedTitle);
        dto.setMessageCount(0);
        dto.setUpdatedAt(getCurrentTimestamp());
        return dto;
    }

    @Override
    public ConversationSessionRespDTO renameSession(String sessionId, Long userId, String title) {
        String normalizedSessionId = sessionId == null ? "" : sessionId.trim();
        String normalizedTitle = normalizeSessionTitle(title);
        Long resolvedUserId = resolveUserId(userId);
        if (!StringUtils.hasText(normalizedSessionId)) {
            ConversationSessionRespDTO empty = new ConversationSessionRespDTO();
            empty.setSessionId(normalizedSessionId);
            empty.setUserId(resolvedUserId);
            empty.setTitle(normalizedTitle);
            empty.setMessageCount(0);
            empty.setUpdatedAt(getCurrentTimestamp());
            return empty;
        }
        if (!isSessionOwnedBy(normalizedSessionId, resolvedUserId)) {
            log.warn("忽略重命名非本人会话请求 - userId: {}, sessionId: {}", resolvedUserId, normalizedSessionId);
            ConversationSessionRespDTO denied = new ConversationSessionRespDTO();
            denied.setSessionId(normalizedSessionId);
            denied.setUserId(resolvedUserId);
            denied.setTitle(normalizedTitle);
            denied.setMessageCount(0);
            denied.setUpdatedAt(getCurrentTimestamp());
            return denied;
        }

        MessageDO meta = messageMapper.selectOne(Wrappers.lambdaQuery(MessageDO.class)
            .eq(MessageDO::getSessionId, normalizedSessionId)
            .eq(MessageDO::getRole, "system")
            .and(wrapper -> wrapper
                .eq(MessageDO::getContent, SESSION_MARKER)
                .or()
                .likeRight(MessageDO::getContent, SESSION_META_PREFIX))
            .orderByAsc(MessageDO::getId)
            .last("limit 1"));
        if (meta == null) {
            persistMessage(normalizedSessionId, resolvedUserId, "system", buildSessionMetaContent(normalizedTitle));
        } else {
            MessageDO update = new MessageDO();
            update.setId(meta.getId());
            update.setContent(buildSessionMetaContent(normalizedTitle));
            messageMapper.updateById(update);
        }

        ConversationSessionRespDTO dto = new ConversationSessionRespDTO();
        dto.setSessionId(normalizedSessionId);
        dto.setUserId(resolvedUserId);
        dto.setTitle(normalizedTitle);
        dto.setUpdatedAt(getCurrentTimestamp());
        dto.setMessageCount(listMessages(normalizedSessionId, 500).size());
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(String sessionId, Long userId) {
        String normalizedSessionId = sessionId == null ? "" : sessionId.trim();
        if (!StringUtils.hasText(normalizedSessionId)) {
            return;
        }
        Long resolvedUserId = resolveUserId(userId);
        if (!isSessionOwnedBy(normalizedSessionId, resolvedUserId)) {
            log.warn("忽略删除非本人会话请求 - userId: {}, sessionId: {}", resolvedUserId, normalizedSessionId);
            return;
        }

        LambdaQueryWrapper<MessageDO> messageQuery = Wrappers.lambdaQuery(MessageDO.class)
            .eq(MessageDO::getSessionId, normalizedSessionId);
        List<MessageDO> rows = messageMapper.selectList(messageQuery);
        List<Long> messageIds = rows == null ? List.of() : rows.stream()
            .map(MessageDO::getId)
            .filter(id -> id != null)
            .collect(Collectors.toList());
        if (!messageIds.isEmpty()) {
            sourceRepository.delete(Wrappers.lambdaQuery(MessageSourceDO.class)
                .in(MessageSourceDO::getMessageId, messageIds));
            messageFeedbackMapper.delete(Wrappers.lambdaQuery(MessageFeedbackDO.class)
                .in(MessageFeedbackDO::getMessageId, messageIds));
        }

        messageSummaryMapper.delete(Wrappers.lambdaQuery(MessageSummaryDO.class)
            .eq(MessageSummaryDO::getSessionId, normalizedSessionId));
        chatTraceMetricMapper.delete(Wrappers.lambdaQuery(ChatTraceMetricDO.class)
            .eq(ChatTraceMetricDO::getSessionId, normalizedSessionId));
        messageMapper.delete(messageQuery);

        sessionHistoryMap.remove(normalizedSessionId);
        userSessionMap.entrySet().removeIf(entry -> normalizedSessionId.equals(entry.getValue()));
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
        // 使用并行加载：历史消息 + 摘要同时从 DB 获取，减少一次串行网络往返
        Long userId = resolveSessionUserIdFromCache(sessionId);
        List<Map<String, String>> context = conversationMemoryService.loadContextParallel(
            sessionId, userId, MAX_HISTORY_SIZE);
        // 同步更新内存缓存（保持 sessionHistoryMap 一致）
        if (!context.isEmpty()) {
            sessionHistoryMap.put(sessionId, cloneHistoryList(context));
        }
        return context;
    }

    @Override
    public Long appendUserMessage(String sessionId, Long userId, String userMessage) {
        String timestamp = getCurrentTimestamp();
        appendHistoryEntry(sessionId, "user", userMessage, timestamp);
        return persistMessage(sessionId, resolveUserId(userId), "user", userMessage);
    }

    @Override
    public Long createAssistantPlaceholder(String sessionId, Long userId) {
        String timestamp = getCurrentTimestamp();
        appendHistoryEntry(sessionId, "assistant", STREAM_PLACEHOLDER, timestamp);
        return persistMessage(sessionId, resolveUserId(userId), "assistant", STREAM_PLACEHOLDER);
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
        Long userId = loadUserIdByMessageId(assistantMessageId);
        conversationMemoryService.scheduleSummary(sessionId, userId);
    }

    @Override
    public void failAssistantMessage(String sessionId, Long assistantMessageId, String fallbackResponse) {
        completeAssistantMessage(sessionId, assistantMessageId, fallbackResponse, List.of());
    }

    @Override
    public List<ConversationSessionRespDTO> listSessions(Long userId) {
        Long resolvedUserId = resolveUserId(userId);
        try {
            LambdaQueryWrapper<MessageDO> queryWrapper = Wrappers.lambdaQuery(MessageDO.class)
                .eq(MessageDO::getUserId, resolvedUserId)
                .orderByDesc(MessageDO::getCreatedAt);
            List<MessageDO> rows = messageMapper.selectList(queryWrapper);
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }

            Map<String, List<MessageDO>> grouped = rows.stream()
                .filter(item -> item != null && StringUtils.hasText(item.getSessionId()))
                .collect(Collectors.groupingBy(MessageDO::getSessionId));

            List<ConversationSessionRespDTO> result = new ArrayList<>();
            for (Map.Entry<String, List<MessageDO>> entry : grouped.entrySet()) {
                List<MessageDO> messages = entry.getValue();
                if (messages == null || messages.isEmpty()) {
                    continue;
                }
                MessageDO latest = messages.stream()
                    .filter(item -> item.getCreatedAt() != null)
                    .max((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()))
                    .orElse(messages.get(0));
                String title = messages.stream()
                    .map(MessageDO::getContent)
                    .map(this::extractSessionTitle)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElseGet(() -> messages.stream()
                    .filter(item -> "user".equalsIgnoreCase(item.getRole()))
                    .map(MessageDO::getContent)
                    .filter(content -> StringUtils.hasText(content) && !isIgnoredContent(content))
                    .findFirst()
                    .map(content -> content.length() > 24 ? content.substring(0, 24) : content)
                    .orElse("新会话"));
                int messageCount = (int) messages.stream()
                    .filter(item -> StringUtils.hasText(item.getContent()) && !isIgnoredContent(item.getContent()))
                    .count();

                ConversationSessionRespDTO dto = new ConversationSessionRespDTO();
                dto.setSessionId(entry.getKey());
                dto.setUserId(latest == null ? resolvedUserId : latest.getUserId());
                dto.setTitle(title);
                dto.setMessageCount(messageCount);
                dto.setUpdatedAt(formatTimestamp(latest == null ? null : latest.getCreatedAt()));
                result.add(dto);
            }
            result.sort((left, right) -> {
                if (left.getUpdatedAt() == null) {
                    return 1;
                }
                if (right.getUpdatedAt() == null) {
                    return -1;
                }
                return right.getUpdatedAt().compareTo(left.getUpdatedAt());
            });
            return result;
        } catch (Exception e) {
            log.debug("查询会话列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<ConversationMessageRespDTO> listMessages(String sessionId, int limit) {
        if (!StringUtils.hasText(sessionId)) {
            return List.of();
        }
        int finalLimit = Math.max(1, Math.min(limit, 500));
        try {
            LambdaQueryWrapper<MessageDO> queryWrapper = Wrappers.lambdaQuery(MessageDO.class)
                .eq(MessageDO::getSessionId, sessionId.trim())
                .orderByDesc(MessageDO::getId)
                .last("limit " + finalLimit);
            List<MessageDO> latestRows = messageMapper.selectList(queryWrapper);
            if (latestRows == null || latestRows.isEmpty()) {
                return List.of();
            }
            Collections.reverse(latestRows);

            Set<Long> messageIds = latestRows.stream()
                .map(MessageDO::getId)
                .filter(id -> id != null)
                .collect(Collectors.toCollection(HashSet::new));
            Map<Long, List<RetrievalMatch>> sourceMap = loadSourcesByMessageIds(messageIds);

            List<ConversationMessageRespDTO> result = new ArrayList<>();
            for (MessageDO item : latestRows) {
                if (item == null || !StringUtils.hasText(item.getContent()) || isIgnoredContent(item.getContent())) {
                    continue;
                }
                ConversationMessageRespDTO dto = new ConversationMessageRespDTO();
                dto.setId(item.getId());
                dto.setRole(item.getRole());
                dto.setContent(item.getContent());
                dto.setCreatedAt(formatTimestamp(item.getCreatedAt()));
                dto.setSources(sourceMap.getOrDefault(item.getId(), List.of()));
                result.add(dto);
            }
            return result;
        } catch (Exception e) {
            log.debug("查询会话消息失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void appendHistoryEntry(String sessionId,
                                    String role,
                                    String content,
                                    String timestamp) {
        List<Map<String, String>> history = sessionHistoryMap.computeIfAbsent(sessionId, key -> new ArrayList<>());
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
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    private String loadLatestSessionId(Long userId) {
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
                if (isIgnoredContent(content) || !StringUtils.hasText(content)) {
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

    private Long persistMessage(String sessionId, Long userId, String role, String content) {
        try {
            MessageDO messageDO = new MessageDO();
            messageDO.setSessionId(sessionId);
            messageDO.setUserId(userId);
            messageDO.setRole(role);
            messageDO.setContent(content);
            messageMapper.insert(messageDO);
            return messageDO.getId();
        } catch (Exception e) {
            log.error("持久化对话失败, sessionId={}, userId={}, role={}", sessionId, userId, role, e);
            throw new IllegalStateException("会话持久化失败", e);
        }
    }

    private boolean isIgnoredContent(String content) {
        if (!StringUtils.hasText(content)) {
            return true;
        }
        return STREAM_PLACEHOLDER.equals(content)
            || SESSION_MARKER.equals(content)
            || content.startsWith(SESSION_META_PREFIX);
    }

    private String extractSessionTitle(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        if (content.startsWith(SESSION_META_PREFIX)) {
            String raw = content.substring(SESSION_META_PREFIX.length()).trim();
            return normalizeSessionTitle(raw);
        }
        return "";
    }

    private String buildSessionMetaContent(String title) {
        return SESSION_META_PREFIX + normalizeSessionTitle(title);
    }

    private String normalizeSessionTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return "新会话";
        }
        String text = title.trim();
        if (text.length() > 40) {
            return text.substring(0, 40);
        }
        return text;
    }

    private boolean isSessionOwnedBy(String sessionId, Long userId) {
        try {
            MessageDO any = messageMapper.selectOne(Wrappers.lambdaQuery(MessageDO.class)
                .eq(MessageDO::getSessionId, sessionId)
                .orderByDesc(MessageDO::getId)
                .last("limit 1"));
            if (any == null || any.getUserId() == null) {
                return true;
            }
            return userId.equals(any.getUserId());
        } catch (Exception e) {
            log.debug("校验会话归属失败: {}", e.getMessage());
            return false;
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

    /**
     * 将 null userId 解析为默认值 1L（admin 用户）。
     */
    private Long resolveUserId(Long userId) {
        return userId != null ? userId : 1L;
    }

    private Long loadUserIdByMessageId(Long messageId) {
        if (messageId == null) {
            return null;
        }
        MessageDO messageDO = messageMapper.selectById(messageId);
        return messageDO == null ? null : messageDO.getUserId();
    }

    /**
     * 从内存缓存 userSessionMap 反推 userId（避免额外 DB 查询）。
     * 用于 loadContextParallel 传入 userId 过滤摘要。
     */
    private Long resolveSessionUserIdFromCache(String sessionId) {
        for (Map.Entry<Long, String> entry : userSessionMap.entrySet()) {
            if (sessionId.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private Map<Long, List<RetrievalMatch>> loadSourcesByMessageIds(Set<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<MessageSourceDO> sourceQuery = Wrappers.lambdaQuery(MessageSourceDO.class)
            .in(MessageSourceDO::getMessageId, messageIds)
            .orderByAsc(MessageSourceDO::getId);
        List<MessageSourceDO> sourceRows = sourceRepository.selectList(sourceQuery);
        if (sourceRows == null || sourceRows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<RetrievalMatch>> grouped = new HashMap<>();
        for (MessageSourceDO source : sourceRows) {
            if (source == null || source.getMessageId() == null) {
                continue;
            }
            RetrievalMatch match = new RetrievalMatch();
            match.setFileMd5(source.getDocumentMd5());
            match.setChunkId(source.getChunkId());
            match.setRelevanceScore(source.getRelevanceScore());
            match.setSourceFileName(source.getSourceFileName());
            grouped.computeIfAbsent(source.getMessageId(), key -> new ArrayList<>()).add(match);
        }
        return grouped;
    }
}
