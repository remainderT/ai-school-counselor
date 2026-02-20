package org.buaa.rag.service;

import org.buaa.rag.dto.IntentDecision;

public interface ToolService {

    /**
     * 执行意图对应的工具逻辑，返回用户可读的结果
     */
    String execute(String userId, String userQuery, IntentDecision decision);
}
