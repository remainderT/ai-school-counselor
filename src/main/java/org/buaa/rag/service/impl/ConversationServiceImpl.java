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
import java.util.stream.Collectors;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.dao.entity.ChunkDO;
import org.buaa.rag.dao.entity.ConversationDO;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.MessageDO;
import org.buaa.rag.dao.entity.MessageSourceDO;
import org.buaa.rag.dao.entity.MessageSummaryDO;
import org.buaa.rag.dao.mapper.ChunkMapper;
import org.buaa.rag.dao.mapper.ConversationMapper;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.MessageMapper;
import org.buaa.rag.dao.mapper.MessageSourceMapper;
import org.buaa.rag.dao.mapper.MessageSummaryMapper;
import org.buaa.rag.core.online.memory.ConversationMemoryService;
import org.buaa.rag.dto.resp.ConversationMessageRespDTO;
import org.buaa.rag.dto.resp.ConversationSessionRespDTO;
import org.buaa.rag.service.ConversationService;
import org.buaa.rag.tool.LlmChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, ConversationDO>
        implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);
    private static final int MAX_HISTORY_SIZE = 60;
    private static final String STREAM_PLACEHOLDER = "__STREAMING__";
    private static final int TITLE_MAX_LENGTH = 40;
    private static final String DEFAULT_TITLE = "新会话";
    private static final String CONVERSATION_TITLE_PROMPT = "conversation-title.st";
    private static final DateTimeFormatter ISO_DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final MessageSourceMapper sourceRepository;
    private final MessageSummaryMapper messageSummaryMapper;
    private final ConversationMemoryService conversationMemoryService;
    private final LlmChat llmChat;

    // ------------------------------------------------------------------ //
    //  会话管理
    // ------------------------------------------------------------------ //

    @Override
    public String obtainOrCreateSession(Long userId) {
        Long resolvedUserId = resolveUserId(userId);
        // 尝试复用该用户最近的一个会话
        ConversationDO latest = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getUserId, resolvedUserId)
                        .orderByDesc(ConversationDO::getLastTime)
                        .last("limit 1"));
        if (latest != null) {
            log.info("复用历史会话 - 用户: {}, 会话ID: {}", resolvedUserId, latest.getSessionId());
            return latest.getSessionId();
        }
        // 不存在则新建
        String sessionId = UUID.randomUUID().toString();
        log.info("创建新会话 - 用户: {}, 会话ID: {}", resolvedUserId, sessionId);
        insertConversation(sessionId, resolvedUserId, "新会话");
        return sessionId;
    }

    @Override
    public ConversationSessionRespDTO createSession(Long userId, String title) {
        Long resolvedUserId = resolveUserId(userId);
        String sessionId = UUID.randomUUID().toString();
        String normalizedTitle = normalizeSessionTitle(title);
        insertConversation(sessionId, resolvedUserId, normalizedTitle);

        ConversationSessionRespDTO dto = new ConversationSessionRespDTO();
        dto.setSessionId(sessionId);
        dto.setUserId(resolvedUserId);
        dto.setTitle(normalizedTitle);
        dto.setMessageCount(0);
        dto.setUpdatedAt(getCurrentTimestamp());
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(String sessionId, Long userId) {
        String normalizedSessionId = normalize(sessionId);
        if (!StringUtils.hasText(normalizedSessionId)) {
            return;
        }
        Long resolvedUserId = resolveUserId(userId);
        if (!isSessionOwnedBy(normalizedSessionId, resolvedUserId)) {
            log.warn("忽略删除非本人会话请求 - userId: {}, sessionId: {}", resolvedUserId, normalizedSessionId);
            return;
        }

        // 级联删除：消息来源、反馈、摘要、消息本身
        LambdaQueryWrapper<MessageDO> msgQuery = Wrappers.lambdaQuery(MessageDO.class)
                .eq(MessageDO::getSessionId, normalizedSessionId);
        List<MessageDO> rows = messageMapper.selectList(msgQuery);
        List<Long> messageIds = rows == null ? List.of() : rows.stream()
                .map(MessageDO::getId)
                .filter(id -> id != null)
                .collect(Collectors.toList());
        if (!messageIds.isEmpty()) {
            sourceRepository.delete(Wrappers.lambdaQuery(MessageSourceDO.class)
                    .in(MessageSourceDO::getMessageId, messageIds));
        }
        messageSummaryMapper.delete(Wrappers.lambdaQuery(MessageSummaryDO.class)
                .eq(MessageSummaryDO::getSessionId, normalizedSessionId));
        messageMapper.delete(msgQuery);

        // 删除 conversation 记录（逻辑删除）
        conversationMapper.delete(Wrappers.lambdaQuery(ConversationDO.class)
                .eq(ConversationDO::getSessionId, normalizedSessionId));
    }

    @Override
    public ConversationSessionRespDTO renameSession(String sessionId, Long userId, String title) {
        String normalizedSessionId = normalize(sessionId);
        String normalizedTitle = normalizeSessionTitle(title);
        Long resolvedUserId = resolveUserId(userId);

        if (!StringUtils.hasText(normalizedSessionId)) {
            return buildSessionResp(normalizedSessionId, resolvedUserId, normalizedTitle, 0);
        }
        if (!isSessionOwnedBy(normalizedSessionId, resolvedUserId)) {
            log.warn("忽略重命名非本人会话请求 - userId: {}, sessionId: {}", resolvedUserId, normalizedSessionId);
            return buildSessionResp(normalizedSessionId, resolvedUserId, normalizedTitle, 0);
        }

        ConversationDO conv = conversationMapper.selectOne(Wrappers.lambdaQuery(ConversationDO.class)
                .eq(ConversationDO::getSessionId, normalizedSessionId));
        if (conv == null) {
            log.warn("重命名失败，会话不存在: sessionId={}", normalizedSessionId);
            return buildSessionResp(normalizedSessionId, resolvedUserId, normalizedTitle, 0);
        }
        ConversationDO update = new ConversationDO();
        update.setId(conv.getId());
        update.setTitle(normalizedTitle);
        conversationMapper.updateById(update);

        long count = messageMapper.selectCount(Wrappers.lambdaQuery(MessageDO.class)
                .eq(MessageDO::getSessionId, normalizedSessionId));
        return buildSessionResp(normalizedSessionId, resolvedUserId, normalizedTitle, (int) count);
    }

    @Override
    public String generateAndPersistTitle(String sessionId, String question) {
        if (!StringUtils.hasText(sessionId)) {
            return DEFAULT_TITLE;
        }
        String normalizedSessionId = normalize(sessionId);
        // 查询会话记录
        ConversationDO conv = conversationMapper.selectOne(Wrappers.lambdaQuery(ConversationDO.class)
                .eq(ConversationDO::getSessionId, normalizedSessionId));
        if (conv == null) {
            return DEFAULT_TITLE;
        }
        // 如果已有非默认标题，直接返回
        if (StringUtils.hasText(conv.getTitle()) && !DEFAULT_TITLE.equals(conv.getTitle())) {
            return conv.getTitle();
        }
        // 调用 LLM 生成标题
        String title = generateTitleFromQuestion(question);
        // 持久化到数据库
        ConversationDO update = new ConversationDO();
        update.setId(conv.getId());
        update.setTitle(title);
        conversationMapper.updateById(update);
        log.info("会话标题已生成并持久化 - sessionId: {}, title: {}", normalizedSessionId, title);
        return title;
    }

    /**
     * 通过 LLM 根据用户问题生成简洁会话标题。
     * 失败时降级为截取问题前 N 个字符。
     */
    private String generateTitleFromQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            return DEFAULT_TITLE;
        }
        try {
            String prompt = PromptTemplateLoader.render(CONVERSATION_TITLE_PROMPT, Map.of(
                    "title_max_chars", String.valueOf(TITLE_MAX_LENGTH),
                    "question", question.trim()
            ));
            if (!StringUtils.hasText(prompt)) {
                log.warn("会话标题 prompt 模板加载失败，使用降级策略");
                return buildFallbackTitle(question);
            }
            String title = llmChat.generateCompletion(null, prompt, 100, 0.7, 0.3);
            if (StringUtils.hasText(title)) {
                // 清理可能的多余符号
                title = title.trim()
                        .replaceAll("^[\"'《「【]", "")
                        .replaceAll("[\"'》」】]$", "")
                        .replaceAll("^标题[：:]\\s*", "");
                return title.length() > TITLE_MAX_LENGTH
                        ? title.substring(0, TITLE_MAX_LENGTH)
                        : title;
            }
        } catch (Exception e) {
            log.warn("LLM 生成会话标题失败，使用降级策略: {}", e.getMessage());
        }
        return buildFallbackTitle(question);
    }

    /**
     * 降级标题生成：截取问题前 N 个字符。
     */
    private String buildFallbackTitle(String question) {
        if (!StringUtils.hasText(question)) {
            return DEFAULT_TITLE;
        }
        String cleaned = question.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= TITLE_MAX_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(0, TITLE_MAX_LENGTH) + "...";
    }

    // ------------------------------------------------------------------ //
    //  消息操作
    // ------------------------------------------------------------------ //

    @Override
    public List<Map<String, String>> loadConversationContext(String sessionId) {
        return conversationMemoryService.loadContextParallel(sessionId, null, MAX_HISTORY_SIZE);
    }

    @Override
    public Long appendUserMessage(String sessionId, Long userId, String userMessage) {
        Long resolvedUserId = resolveUserId(userId);
        Long messageId = persistMessage(sessionId, resolvedUserId, "user", userMessage);
        // 更新会话的最近消息时间
        touchConversation(sessionId, resolvedUserId);
        return messageId;
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

    // ------------------------------------------------------------------ //
    //  查询
    // ------------------------------------------------------------------ //

    @Override
    public List<ConversationSessionRespDTO> listSessions(Long userId) {
        Long resolvedUserId = resolveUserId(userId);
        try {
            List<ConversationDO> conversations = conversationMapper.selectList(
                    Wrappers.lambdaQuery(ConversationDO.class)
                            .eq(ConversationDO::getUserId, resolvedUserId)
                            .orderByDesc(ConversationDO::getLastTime));
            if (conversations == null || conversations.isEmpty()) {
                return List.of();
            }

            List<String> sessionIds = conversations.stream()
                    .map(ConversationDO::getSessionId)
                    .collect(Collectors.toList());

            // 批量统计每个会话的消息数
            Map<String, Long> countMap = batchCountMessages(sessionIds);

            return conversations.stream().map(conv -> {
                ConversationSessionRespDTO dto = new ConversationSessionRespDTO();
                dto.setSessionId(conv.getSessionId());
                dto.setUserId(conv.getUserId());
                dto.setTitle(conv.getTitle());
                dto.setMessageCount(countMap.getOrDefault(conv.getSessionId(), 0L).intValue());
                dto.setUpdatedAt(formatTimestamp(conv.getLastTime() != null ? conv.getLastTime() : conv.getCreateTime()));
                return dto;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("查询会话列表失败: userId={}", resolvedUserId, e);
            return List.of();
        }
    }

    @Override
    public List<ConversationMessageRespDTO> listMessages(String sessionId, Long userId, int limit) {
        if (!StringUtils.hasText(sessionId)) {
            return List.of();
        }
        // 校验会话归属，防止水平越权
        Long resolvedUserId = resolveUserId(userId);
        if (!isSessionOwnedBy(sessionId.trim(), resolvedUserId)) {
            log.warn("拒绝查看非本人会话历史 - userId: {}, sessionId: {}", resolvedUserId, sessionId);
            return List.of();
        }
        return listMessages(sessionId, limit);
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
                if (item == null || !StringUtils.hasText(item.getContent())
                        || isIgnoredContent(item.getContent())) {
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

    // ------------------------------------------------------------------ //
    //  内部工具方法
    // ------------------------------------------------------------------ //

    /** 向 conversation 表插入一条新会话记录 */
    private void insertConversation(String sessionId, Long userId, String title) {
        ConversationDO conv = ConversationDO.builder()
                .sessionId(sessionId)
                .userId(userId)
                .title(title)
                .lastTime(LocalDateTime.now())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .delFlag(0)
                .build();
        conversationMapper.insert(conv);
    }

    /** 更新会话的最近消息时间（不存在时自动补录） */
    private void touchConversation(String sessionId, Long userId) {
        try {
            ConversationDO conv = conversationMapper.selectOne(Wrappers.lambdaQuery(ConversationDO.class)
                    .eq(ConversationDO::getSessionId, sessionId));
            if (conv == null) {
                insertConversation(sessionId, userId, "新会话");
            } else {
                ConversationDO update = new ConversationDO();
                update.setId(conv.getId());
                update.setLastTime(LocalDateTime.now());
                conversationMapper.updateById(update);
            }
        } catch (Exception e) {
            log.warn("更新会话时间失败: sessionId={}", sessionId, e);
        }
    }

    /** 校验会话是否属于指定用户 */
    private boolean isSessionOwnedBy(String sessionId, Long userId) {
        try {
            ConversationDO conv = conversationMapper.selectOne(Wrappers.lambdaQuery(ConversationDO.class)
                    .eq(ConversationDO::getSessionId, sessionId));
            if (conv == null) {
                return false;
            }
            return userId.equals(conv.getUserId());
        } catch (Exception e) {
            log.error("校验会话归属异常（拒绝访问）: sessionId={}, userId={}", sessionId, userId, e);
            return false;
        }
    }

    /** 批量统计各会话的消息数（只统计 user/assistant 角色） */
    private Map<String, Long> batchCountMessages(List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        try {
            List<MessageDO> rows = messageMapper.selectList(Wrappers.lambdaQuery(MessageDO.class)
                    .in(MessageDO::getSessionId, sessionIds)
                    .in(MessageDO::getRole, "user", "assistant")
                    .select(MessageDO::getSessionId));
            return rows.stream()
                    .collect(Collectors.groupingBy(MessageDO::getSessionId, Collectors.counting()));
        } catch (Exception e) {
            log.warn("批量统计消息数失败", e);
            return Map.of();
        }
    }

    private Long persistMessage(String sessionId, Long userId, String role, String content) {
        try {
            MessageDO msg = new MessageDO();
            msg.setSessionId(sessionId);
            msg.setUserId(userId);
            msg.setRole(role);
            msg.setContent(content);
            messageMapper.insert(msg);
            return msg.getId();
        } catch (Exception e) {
            log.error("持久化对话失败, sessionId={}, userId={}, role={}", sessionId, userId, role, e);
            throw new IllegalStateException("会话持久化失败", e);
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
        Map<String, String> chunkTextMap = loadChunkTextMap(sourceRows);
        Map<String, String> sourceNameMap = loadDocumentNameMap(sourceRows);
        Map<Long, List<RetrievalMatch>> grouped = new HashMap<>();
        for (MessageSourceDO source : sourceRows) {
            if (source == null || source.getMessageId() == null) {
                continue;
            }
            RetrievalMatch match = new RetrievalMatch();
            match.setFileMd5(source.getDocumentMd5());
            match.setChunkId(source.getChunkId());
            match.setTextContent(chunkTextMap.get(buildChunkKey(source.getDocumentMd5(), source.getChunkId())));
            match.setRelevanceScore(source.getRelevanceScore());
            String sourceName = source.getSourceFileName();
            if (!StringUtils.hasText(sourceName)) {
                sourceName = sourceNameMap.get(source.getDocumentMd5());
            }
            match.setSourceFileName(sourceName);
            grouped.computeIfAbsent(source.getMessageId(), key -> new ArrayList<>()).add(match);
        }
        return grouped;
    }

    private Map<String, String> loadChunkTextMap(List<MessageSourceDO> sourceRows) {
        Set<String> md5Set = sourceRows.stream()
                .map(MessageSourceDO::getDocumentMd5)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (md5Set.isEmpty()) {
            return Map.of();
        }
        List<DocumentDO> documents = documentMapper.selectList(Wrappers.lambdaQuery(DocumentDO.class)
                .in(DocumentDO::getMd5Hash, md5Set)
                .select(DocumentDO::getId, DocumentDO::getMd5Hash, DocumentDO::getOriginalFileName));
        if (documents == null || documents.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> md5ByDocumentId = documents.stream()
                .filter(d -> d.getId() != null && StringUtils.hasText(d.getMd5Hash()))
                .collect(Collectors.toMap(DocumentDO::getId, DocumentDO::getMd5Hash, (l, r) -> l));
        if (md5ByDocumentId.isEmpty()) {
            return Map.of();
        }
        List<ChunkDO> chunks = chunkMapper.selectList(Wrappers.lambdaQuery(ChunkDO.class)
                .in(ChunkDO::getDocumentId, md5ByDocumentId.keySet())
                .select(ChunkDO::getDocumentId, ChunkDO::getFragmentIndex, ChunkDO::getTextData));
        if (chunks == null || chunks.isEmpty()) {
            return Map.of();
        }
        Map<String, String> chunkTextMap = new HashMap<>();
        for (ChunkDO chunk : chunks) {
            if (chunk == null || chunk.getDocumentId() == null || chunk.getFragmentIndex() == null) {
                continue;
            }
            String md5 = md5ByDocumentId.get(chunk.getDocumentId());
            if (!StringUtils.hasText(md5) || !StringUtils.hasText(chunk.getTextData())) {
                continue;
            }
            chunkTextMap.put(buildChunkKey(md5, chunk.getFragmentIndex()), chunk.getTextData());
        }
        return chunkTextMap;
    }

    private Map<String, String> loadDocumentNameMap(List<MessageSourceDO> sourceRows) {
        Set<String> md5Set = sourceRows.stream()
                .map(MessageSourceDO::getDocumentMd5)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (md5Set.isEmpty()) {
            return Map.of();
        }
        List<DocumentDO> documents = documentMapper.selectList(Wrappers.lambdaQuery(DocumentDO.class)
                .in(DocumentDO::getMd5Hash, md5Set)
                .select(DocumentDO::getMd5Hash, DocumentDO::getOriginalFileName));
        if (documents == null || documents.isEmpty()) {
            return Map.of();
        }
        return documents.stream()
                .filter(d -> StringUtils.hasText(d.getMd5Hash()) && StringUtils.hasText(d.getOriginalFileName()))
                .collect(Collectors.toMap(DocumentDO::getMd5Hash, DocumentDO::getOriginalFileName, (l, r) -> l));
    }

    private boolean isIgnoredContent(String content) {
        return !StringUtils.hasText(content) || STREAM_PLACEHOLDER.equals(content);
    }

    private String normalizeSessionTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return "新会话";
        }
        String text = title.trim();
        return text.length() > TITLE_MAX_LENGTH ? text.substring(0, TITLE_MAX_LENGTH) : text;
    }

    private ConversationSessionRespDTO buildSessionResp(String sessionId, Long userId, String title, int count) {
        ConversationSessionRespDTO dto = new ConversationSessionRespDTO();
        dto.setSessionId(sessionId);
        dto.setUserId(userId);
        dto.setTitle(title);
        dto.setMessageCount(count);
        dto.setUpdatedAt(getCurrentTimestamp());
        return dto;
    }

    private String buildChunkKey(String documentMd5, Integer chunkId) {
        return (documentMd5 == null ? "" : documentMd5) + "#" + (chunkId == null ? "null" : chunkId);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(ISO_DATETIME_FMT);
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return getCurrentTimestamp();
        }
        return timestamp.format(ISO_DATETIME_FMT);
    }

    private Long resolveUserId(Long userId) {
        return userId != null ? userId : UserContext.resolvedUserId();
    }
}
