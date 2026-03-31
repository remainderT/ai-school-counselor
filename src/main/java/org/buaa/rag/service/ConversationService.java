package org.buaa.rag.service;

import java.util.List;
import java.util.Map;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.dto.resp.ConversationMessageRespDTO;
import org.buaa.rag.dto.resp.ConversationSessionRespDTO;

public interface ConversationService {

    String obtainOrCreateSession(String userId);

    ConversationSessionRespDTO createSession(String userId, String title);

    void deleteSession(String sessionId, String userIdPrefix);

    ConversationSessionRespDTO renameSession(String sessionId, String userIdPrefix, String title);

    List<Map<String, String>> loadConversationHistory(String sessionId);

    List<Map<String, String>> loadConversationContext(String sessionId);

    Long appendUserMessage(String sessionId,
                           String userId,
                           String userMessage);

    Long createAssistantPlaceholder(String sessionId,
                                    String userId);

    void completeAssistantMessage(String sessionId,
                                  Long assistantMessageId,
                                  String aiResponse,
                                  List<RetrievalMatch> sources);

    void failAssistantMessage(String sessionId,
                              Long assistantMessageId,
                              String fallbackResponse);

    Long appendToHistory(String sessionId,
                         String userId,
                         String userMessage,
                         String aiResponse,
                         List<RetrievalMatch> sources);

    List<ConversationSessionRespDTO> listSessions(String userIdPrefix);

    List<ConversationMessageRespDTO> listMessages(String sessionId, int limit);
}
