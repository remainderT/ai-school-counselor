package org.buaa.rag.service;

import java.util.List;
import java.util.Map;

import org.buaa.rag.dao.entity.ConversationDO;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.dto.resp.ConversationMessageRespDTO;
import org.buaa.rag.dto.resp.ConversationSessionRespDTO;

import com.baomidou.mybatisplus.extension.service.IService;

public interface ConversationService extends IService<ConversationDO> {

    /** 获取或创建当前会话（用于未指定 sessionId 时自动复用最近一条） */
    String obtainOrCreateSession(Long userId);

    /** 创建新会话 */
    ConversationSessionRespDTO createSession(Long userId, String title);

    /** 删除会话（级联删除消息） */
    void deleteSession(String sessionId, Long userId);

    /** 重命名会话 */
    ConversationSessionRespDTO renameSession(String sessionId, Long userId, String title);

    /** 加载会话上下文（用于 RAG 链路） */
    List<Map<String, String>> loadConversationContext(String sessionId);

    /** 追加用户消息 */
    Long appendUserMessage(String sessionId, Long userId, String userMessage);

    /** 创建助手占位消息（流式回答开始前写入） */
    Long createAssistantPlaceholder(String sessionId, Long userId);

    /** 完成助手消息写入（流式结束后更新占位并保存来源） */
    void completeAssistantMessage(String sessionId,
                                  Long assistantMessageId,
                                  Long userId,
                                  String aiResponse,
                                  List<RetrievalMatch> sources);

    /** 标记助手消息失败 */
    void failAssistantMessage(String sessionId, Long assistantMessageId, Long userId, String fallbackResponse);

    /** 查询当前用户的会话列表 */
    List<ConversationSessionRespDTO> listSessions(Long userId);

    /** 查询会话历史消息 */
    List<ConversationMessageRespDTO> listMessages(String sessionId, int limit);

    /** 查询会话历史消息（带用户归属校验） */
    List<ConversationMessageRespDTO> listMessages(String sessionId, Long userId, int limit);

    /**
     * 根据用户首条问题通过 LLM 生成会话标题，并持久化到数据库。
     * 如果该会话已有非默认标题则跳过。
     *
     * @param sessionId 会话 ID
     * @param question  用户问题
     * @return 生成（或已有）的标题
     */
    String generateAndPersistTitle(String sessionId, String question);
}
