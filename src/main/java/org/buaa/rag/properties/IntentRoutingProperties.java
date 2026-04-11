package org.buaa.rag.properties;

import java.util.LinkedHashMap;
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
    private Map<String, String> toolKeywords = defaultToolKeywords();

    private static Map<String, String> defaultToolKeywords() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("请假", "leave");
        defaults.put("销假", "leave");
        defaults.put("报修", "repair");
        defaults.put("成绩", "score");
        defaults.put("绩点", "score");
        defaults.put("天气", "weather");
        defaults.put("气温", "weather");
        defaults.put("降雨", "weather");
        defaults.put("下雨", "weather");
        return defaults;
    }
}
