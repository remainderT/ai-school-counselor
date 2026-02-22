package org.buaa.rag.service;

import java.util.List;
import java.util.Map;

import org.buaa.rag.dto.RetrievalMatch;

public interface ConversationService {

    String obtainOrCreateSession(String userId);

    List<Map<String, String>> loadConversationHistory(String sessionId);

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
}
