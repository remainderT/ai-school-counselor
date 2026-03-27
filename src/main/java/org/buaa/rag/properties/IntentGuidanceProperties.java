package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 意图歧义引导配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.intent-guidance")
public class IntentGuidanceProperties {

    /**
     * 是否启用基于意图候选的引导澄清
     */
    private boolean enabled = true;

    /**
     * 命中阈值：候选低于该值默认不直接命中
     */
    private double hitThreshold = 0.58;

    /**
     * 歧义比例阈值（次高分 / 最高分）
     */
    private double ambiguityRatio = 0.88;

    /**
     * 最大候选数量
     */
    private int maxCandidates = 4;

    /**
     * 最大引导选项数量
     */
    private int maxOptions = 3;

    /**
     * LLM 树分类最低分（低于此值会被过滤）
     */
    private double llmMinScore = 0.45;
}
