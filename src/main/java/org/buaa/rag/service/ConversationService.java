package org.buaa.rag.service;

import java.util.List;
import java.util.Map;

import org.buaa.rag.dao.entity.MessageDO;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.dto.resp.ConversationMessageRespDTO;
import org.buaa.rag.dto.resp.ConversationSessionRespDTO;

import com.baomidou.mybatisplus.extension.service.IService;

public interface ConversationService extends IService<MessageDO> {

    /**
     * 获取或创建当前会话
     */
    String obtainOrCreateSession(Long userId);

    /**
     * 创建新会话
     */
    ConversationSessionRespDTO createSession(Long userId, String title);

    /**
     * 删除会话
     */
    void deleteSession(String sessionId, Long userId);

    /**
     * 重命名会话
     */
    ConversationSessionRespDTO renameSession(String sessionId, Long userId, String title);

    /**
     * 加载会话上下文
     */
    List<Map<String, String>> loadConversationContext(String sessionId);

    /**
     * 追加用户消息
     */
    Long appendUserMessage(String sessionId, Long userId, String userMessage);

    /**
     * 创建助手占位消息
     */
    Long createAssistantPlaceholder(String sessionId, Long userId);

    /**
     * 完成助手消息写入
     */
    void completeAssistantMessage(String sessionId,
                                  Long assistantMessageId,
                                  Long userId,
                                  String aiResponse,
                                  List<RetrievalMatch> sources);

    /**
     * 标记助手消息失败
     */
    void failAssistantMessage(String sessionId, Long assistantMessageId, Long userId, String fallbackResponse);

    /**
     * 查询会话列表
     */
    List<ConversationSessionRespDTO> listSessions(Long userId);

    /**
     * 查询会话消息列表
     */
    List<ConversationMessageRespDTO> listMessages(String sessionId, int limit);
}
