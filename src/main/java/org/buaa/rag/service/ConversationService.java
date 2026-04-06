package org.buaa.rag.service;

import java.util.List;
import java.util.Map;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.dto.resp.ConversationMessageRespDTO;
import org.buaa.rag.dto.resp.ConversationSessionRespDTO;

public interface ConversationService {

    String obtainOrCreateSession(Long userId);

    ConversationSessionRespDTO createSession(Long userId, String title);

    void deleteSession(String sessionId, Long userId);

    ConversationSessionRespDTO renameSession(String sessionId, Long userId, String title);

    List<Map<String, String>> loadConversationHistory(String sessionId);

    List<Map<String, String>> loadConversationContext(String sessionId);

    Long appendUserMessage(String sessionId,
                           Long userId,
                           String userMessage);

    Long createAssistantPlaceholder(String sessionId,
                                    Long userId);

    void completeAssistantMessage(String sessionId,
                                  Long assistantMessageId,
                                  String aiResponse,
                                  List<RetrievalMatch> sources);

    void failAssistantMessage(String sessionId,
                              Long assistantMessageId,
                              String fallbackResponse);

    List<ConversationSessionRespDTO> listSessions(Long userId);

    List<ConversationMessageRespDTO> listMessages(String sessionId, int limit);
}
