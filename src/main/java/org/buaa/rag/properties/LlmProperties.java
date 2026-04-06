package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 大语言模型配置属性
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class LlmProperties {

    private PromptTemplate promptTemplate = new PromptTemplate();
    private GenerationParams generationParams = new GenerationParams();

    /**
     * 提示词模板配置
     */
    @Data
    public static class PromptTemplate {
        // rules 已迁移到 prompts/system-rules.st
        /** 参考资料开始标记 */
        private String refStart;
        /** 参考资料结束标记 */
        private String refEnd;
        /** 无检索结果提示语 */
        private String noResultText;
    }

    /**
     * 生成参数配置
     */
    @Data
    public static class GenerationParams {
        /** 温度参数（控制随机性） */
        private Double temperature = 0.3;
        /** 最大生成token数 */
        private Integer maxTokens = 2000;
        /** Top-P采样参数 */
        private Double topP = 0.9;
    }
}
