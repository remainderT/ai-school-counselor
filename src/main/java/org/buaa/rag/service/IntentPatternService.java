package org.buaa.rag.service;

import java.util.Optional;
import org.buaa.rag.dto.IntentDecision;

public interface IntentPatternService {

    /**
     * 使用语义匹配命中意图模式，命中返回 IntentDecision，否则 empty
     */
    Optional<IntentDecision> semanticRoute(String query);

    /**
     * 将高置信度的真实问法写回意图样本索引，提升路由命中率
     */
    void recordPattern(String level1, String level2, String query, double confidence);

    /**
     * 初始化意图样本索引并写入种子数据（幂等）
     */
    void initPatterns();
}
