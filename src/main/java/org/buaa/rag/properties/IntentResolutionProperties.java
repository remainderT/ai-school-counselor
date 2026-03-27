package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 多意图解析配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.intent-resolution")
public class IntentResolutionProperties {

    /**
     * 每个子问题最多保留候选数
     */
    private int perQueryMaxCandidates = 3;

    /**
     * 总候选数上限
     */
    private int maxTotalCandidates = 6;

    /**
     * 候选最低分
     */
    private double minScore = 0.5;
}
