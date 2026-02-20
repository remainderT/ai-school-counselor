package org.buaa.rag.service;

import org.buaa.rag.dto.IntentDecision;

public interface IntentRouterService {

    /**
     * 基于规则/语义/LLM 的混合路由，输出意图决策
     */
    IntentDecision decide(String userId, String query);
}
