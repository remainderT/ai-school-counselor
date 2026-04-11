package org.buaa.rag.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    /** 反向索引：sessionId → userId，避免 loadConversationContext 中 O(N) 线性扫 */
    private final Map<String, Long> sessionUserMap = new ConcurrentHashMap<>();

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
                sessionUserMap.put(existingSession, resolvedUserId);
                return existingSession;
            }
            String newSessionId = UUID.randomUUID().toString();
            log.info("创建新会话 - 用户: {}, 会话ID: {}", resolvedUserId, newSessionId);
            sessionUserMap.put(newSessionId, resolvedUserId);
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
        sessionUserMap.put(sessionId, resolvedUserId);

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
        long count = messageMapper.selectCount(Wrappers.lambdaQuery(MessageDO.class)
            .eq(MessageDO::getSessionId, normalizedSessionId));
        dto.setMessageCount((int) count);
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

        sessionUserMap.remove(normalizedSessionId);
        userSessionMap.entrySet().removeIf(entry -> normalizedSessionId.equals(entry.getValue()));
    }

    @Override
    public List<Map<String, String>> loadConversationContext(String sessionId) {
        // O(1) 通过反向索引获取 userId（避免遍历 userSessionMap）
        Long userId = sessionUserMap.get(sessionId);
        // 并行加载：历史消息 + 摘要同时从 DB 获取
        return conversationMemoryService.loadContextParallel(sessionId, userId, MAX_HISTORY_SIZE);
    }

    @Override
    public Long appendUserMessage(String sessionId, Long userId, String userMessage) {
        return persistMessage(sessionId, resolveUserId(userId), "user", userMessage);
    }

    @Override
    public Long createAssistantPlaceholder(String sessionId, Long userId) {
        return persistMessage(sessionId, resolveUserId(userId), "assistant", STREAM_PLACEHOLDER);
    }

    @Override
    public void completeAssistantMessage(String sessionId,
                                          Long assistantMessageId,
                                          Long userId,
                                          String aiResponse,
                                          List<RetrievalMatch> sources) {
        String response = StringUtils.hasText(aiResponse) ? aiResponse : "（本次回答为空）";
        if (assistantMessageId != null) {
            MessageDO update = new MessageDO();
            update.setId(assistantMessageId);
            update.setContent(response);
            messageMapper.updateById(update);
        }
        persistSources(assistantMessageId, sources);
        conversationMemoryService.scheduleSummary(sessionId, resolveUserId(userId));
    }


    @Override
    public void failAssistantMessage(String sessionId, Long assistantMessageId, Long userId, String fallbackResponse) {
        completeAssistantMessage(sessionId, assistantMessageId, userId, fallbackResponse, List.of());
    }

    // 最多加载最近 N 条消息用于构建会话列表，避免全表扫描
    private static final int LIST_SESSIONS_MSG_LIMIT = 2000;

    @Override
    public List<ConversationSessionRespDTO> listSessions(Long userId) {
        Long resolvedUserId = resolveUserId(userId);
        try {
            LambdaQueryWrapper<MessageDO> queryWrapper = Wrappers.lambdaQuery(MessageDO.class)
                .eq(MessageDO::getUserId, resolvedUserId)
                .orderByDesc(MessageDO::getCreatedAt)
                .last("limit " + LIST_SESSIONS_MSG_LIMIT);
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
                    .max(Comparator.comparing(MessageDO::getCreatedAt))
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
            result.sort(Comparator.comparing(
                ConversationSessionRespDTO::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())
            ));
            return result;
        } catch (Exception e) {
            log.warn("查询会话列表失败: userId={}", resolvedUserId, e);
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
            log.warn("查询会话消息失败: sessionId={}", sessionId, e);
            return List.of();
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
            log.warn("读取历史会话失败: userId={}, reason={}", userId, e.getMessage());
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
            log.error("校验会话归属时发生异常（拒绝访问）: sessionId={}, userId={}", sessionId, userId, e);
            return false; // fail-safe：异常时拒绝访问
        }
    }

    private void persistSources(Long messageId, List<RetrievalMatch> sources) {
        if (messageId == null || sources == null || sources.isEmpty()) {
            return;
        }
        try {
            List<MessageSourceDO> rows = sources.stream()
                .map(match -> MessageSourceDO.builder()
                    .messageId(messageId)
                    .documentMd5(match.getFileMd5())
                    .chunkId(match.getChunkId())
                    .relevanceScore(match.getRelevanceScore())
                    .sourceFileName(match.getSourceFileName())
                    .build())
                .collect(Collectors.toList());
            com.baomidou.mybatisplus.extension.toolkit.Db.saveBatch(rows);
        } catch (Exception e) {
            log.warn("持久化消息来源失败: messageId={}", messageId, e);
        }
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return getCurrentTimestamp();
        }
        return timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    private Long resolveUserId(Long userId) {
        return userId != null ? userId : 1L;
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
