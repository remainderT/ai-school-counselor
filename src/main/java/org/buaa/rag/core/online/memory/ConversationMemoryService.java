package org.buaa.rag.core.online.memory;

import java.util.List;
import java.util.Map;

/**
 * 会话记忆服务接口：负责历史上下文加载和摘要压缩调度。
 */
public interface ConversationMemoryService {

    /**
     * 并行加载历史消息 + 最新摘要，组装上下文列表。
     *
     * @param sessionId  会话 ID
     * @param userId     用户 ID（可为 null）
     * @param maxHistory 最大历史消息条数
     * @return 上下文消息列表（role + content）
     */
    List<Map<String, String>> loadContextParallel(String sessionId, Long userId, int maxHistory);

    /**
     * 在 assistant 消息写完后调用，异步触发摘要压缩检查。
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     */
    void scheduleSummary(String sessionId, Long userId);
}
