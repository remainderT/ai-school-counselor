package org.buaa.rag.module.memory;

import java.util.List;
import java.util.Map;

public interface ConversationMemoryService {

    List<Map<String, String>> buildContext(String sessionId, List<Map<String, String>> history);

    void scheduleSummary(String sessionId, String userId);
}
