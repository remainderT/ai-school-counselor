package org.buaa.rag.properties;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 意图路由配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.intent-routing")
public class IntentRoutingProperties {

    /**
     * 语义路由直接命中阈值（高于此值直接采信）
     */
    private double semanticDirectThreshold = 0.9;

    /**
     * LLM 意图分类兜底阈值（高于此值走 RAG / TOOL）
     */
    private double llmRagThreshold = 0.5;

    /**
     * ES 语义路由最低匹配分数（低于此值丢弃匹配）
     */
    private double semanticMinScore = 0.7;

    /**
     * 关键词 → 工具名映射，用于轻量工具路由
     */
    private Map<String, String> toolKeywords = new HashMap<>(Map.of(
        "请假", "leave",
        "销假", "leave",
        "报修", "repair",
        "成绩", "score",
        "绩点", "score",
        "天气", "weather",
        "气温", "weather",
        "降雨", "weather",
        "下雨", "weather"
    ));
}
