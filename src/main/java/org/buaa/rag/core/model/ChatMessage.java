package org.buaa.rag.core.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.util.StringUtils;

/**
 * 类型化对话消息：替代 {@code Map<String, String>}，提供编译期类型安全。
 *
 * <p>提供与 {@code Map<String, String>} 互转的静态工厂方法，
 * 方便在新旧代码之间平滑过渡，不需要一次性全量替换所有调用点。
 */
public record ChatMessage(String role, String content) {

    /** 标准角色常量 */
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";

    // ──────────────────────── 工厂方法 ────────────────────────

    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ROLE_SYSTEM, content);
    }

    // ──────────────────────── Map 互转（平滑迁移） ────────────────────────

    /**
     * 将 {@code Map<String, String>} 转换为 {@code ChatMessage}。
     * 若 role 或 content 为空则返回 null。
     */
    public static ChatMessage fromMap(Map<String, String> map) {
        if (map == null) {
            return null;
        }
        String role = map.get("role");
        String content = map.get("content");
        if (!StringUtils.hasText(role) || !StringUtils.hasText(content)) {
            return null;
        }
        return new ChatMessage(role.toLowerCase(), content);
    }

    /**
     * 将 {@code ChatMessage} 转换为 {@code Map<String, String>}（保持与旧 API 兼容）。
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>(2);
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    /**
     * 批量将 {@code List<Map<String, String>>} 转为 {@code List<ChatMessage>}，过滤无效项。
     */
    public static List<ChatMessage> fromMapList(List<Map<String, String>> mapList) {
        if (mapList == null || mapList.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> result = new ArrayList<>(mapList.size());
        for (Map<String, String> map : mapList) {
            ChatMessage msg = fromMap(map);
            if (msg != null) {
                result.add(msg);
            }
        }
        return result;
    }

    /**
     * 批量将 {@code List<ChatMessage>} 转为 {@code List<Map<String, String>>}。
     */
    public static List<Map<String, String>> toMapList(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> result = new ArrayList<>(messages.size());
        for (ChatMessage msg : messages) {
            if (msg != null) {
                result.add(msg.toMap());
            }
        }
        return result;
    }
}
