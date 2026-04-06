package org.buaa.rag.core.online.memory;

import java.util.List;
import java.util.Map;

public interface ConversationMemoryService {

    /**
     * 构建上下文：基于已加载的历史消息 + 数据库摘要，返回注入摘要后的上下文列表。
     * 适用于调用方已有历史记录、只需补充摘要的场景。
     */
    List<Map<String, String>> buildContext(String sessionId, List<Map<String, String>> history);

    /**
     * 并行加载：同时从 DB 获取历史消息和最新摘要，组合后返回完整上下文。
     * 比串行先加载历史再注入摘要减少一次网络往返延迟。
     *
     * @param sessionId 会话ID
     * @param userId    用户ID（用于摘要查询过滤）
     * @param maxHistory 最多加载的历史消息条数
     * @return 注入摘要 system 消息的完整上下文列表
     */
    List<Map<String, String>> loadContextParallel(String sessionId, Long userId, int maxHistory);

    /**
     * 触发异步摘要压缩（如达到阈值则在后台生成新摘要）。
     */
    void scheduleSummary(String sessionId, Long userId);
}
