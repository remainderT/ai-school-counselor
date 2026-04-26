package org.buaa.rag.properties;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * RAG检索增强配置属性
 */
@Component
@ConfigurationProperties(prefix = "rag")
@Data
public class RagProperties {

    private QueryPreprocess queryPreprocess = new QueryPreprocess();
    private Fusion fusion = new Fusion();
    private Rerank rerank = new Rerank();
    private Crag crag = new Crag();
    private Feedback feedback = new Feedback();
    private SemanticCache semanticCache = new SemanticCache();
    private Memory memory = new Memory();
    private Retrieval retrieval = new Retrieval();
    private Prompt prompt = new Prompt();

    @Data
    public static class QueryPreprocess {
        /** 是否启用改写+拆分（关闭后直接透传原始问题） */
        private boolean enabled = true;
        /** 最多拆分的子问题数 */
        private int maxSubQuestions = 4;
        /**
         * 词项归一化映射：key 为同义词/别名，value 为标准词。
         * 例如：毕设 → 毕业设计，辅导员 → 导师
         * 按最长 key 优先替换，避免短词吞并长词。
         */
        private java.util.Map<String, String> termMapping = new java.util.LinkedHashMap<>();
    }

    @Data
    public static class Fusion {
        private boolean enabled = true;
        private int rrfK = 60;
    }

    @Data
    public static class Rerank {
        private boolean enabled = true;
        private int maxCandidates = 8;
        private int snippetLength = 200;

        /**
         * Rerank 提供商优先级列表，按顺序依次尝试，失败自动降级。
         * 可选值：dashscope, llm, noop
         * 默认：dashscope → llm → noop
         */
        private List<String> providerOrder;

        /**
         * 百炼 Rerank 模型配置
         */
        private RerankProvider dashscope = new RerankProvider();
    }

    @Data
    public static class RerankProvider {
        /** Rerank API 的 base URL */
        private String baseUrl = "https://dashscope.aliyuncs.com";
        /** API Key（留空时从 spring.ai.dashscope.api-key 继承） */
        private String apiKey;
        /** Rerank 模型名称 */
        private String model = "gte-rerank";
        /** HTTP 请求超时时间（秒） */
        private int timeoutSeconds = 15;
    }

    @Data
    public static class Crag {
        private boolean enabled = true;
        private boolean useLlm = true;
        private double minScore = 0.2;
        private int reviewTopK = 3;
        private int fallbackMultiplier = 2;
        // prompt 已迁移到 prompts/crag-evaluate.st
        // clarifyPrompt 已迁移到 prompts/crag-clarify.st

        /**
         * 歧义指示词列表：用户消息包含这些词时判定为可能需要澄清
         */
        private List<String> ambiguityWords = List.of("这个", "那个", "之前", "上面", "怎么弄", "怎么办");
        /**
         * 歧义判定最小消息长度（短于此值视为模糊）
         */
        private int ambiguityMinLength = 6;
    }

    @Data
    public static class Feedback {
        private boolean enabled = true;
        private double maxBoost = 0.15;
    }

    @Data
    public static class SemanticCache {
        private boolean enabled = true;
        private double minSimilarity = 0.92;
        private int maxEntries = 300;
        private long ttlMinutes = 120;
    }

    @Data
    public static class Memory {
        /**
         * 短期记忆保留轮数（1轮=用户+助手）
         */
        private int historyKeepTurns = 4;
        /**
         * 是否启用长期摘要
         */
        private boolean summaryEnabled = true;
        /**
         * 触发摘要的轮数阈值（需大于 historyKeepTurns）
         */
        private int summaryStartTurns = 8;
        /**
         * 摘要最大字符数
         */
        private int summaryMaxChars = 320;
        /**
         * 摘要生成时的最大输出 token
         */
        private int summaryMaxTokens = 180;
        /**
         * 两次摘要生成之间最小新增消息数
         */
        private int minDeltaMessages = 4;
        /**
         * loadContextParallel 的默认最大历史消息数
         */
        private int defaultMaxHistory = 60;
    }

    @Data
    public static class Retrieval {
        /**
         * 子问题检索默认 topK
         */
        private int defaultTopK = 5;
        /**
         * 子问题检索最大 topK
         */
        private int maxTopK = 10;
        /**
         * 低质量判定阈值（top-1 分数低于此值视为低质量）
         */
        private double minAcceptableScore = 0.25;
    }

    @Data
    public static class Prompt {
        /**
         * 参考上下文中每条片段最大字符数
         */
        private int maxReferenceLength = 300;
        /**
         * 最大来源引用条数
         */
        private int maxSourceReferenceCount = 5;
        /**
         * KB 纯检索场景温度
         */
        private double temperatureKb = 0.0;
        /**
         * Tool 结果混合场景温度
         */
        private double temperatureTool = 0.3;
        /**
         * KB 纯检索场景 topP
         */
        private double topPKb = 1.0;
        /**
         * Tool 混合场景 topP
         */
        private double topPTool = 0.8;
        /**
         * 单意图回答最大输出 token
         */
        private int singleIntentMaxTokens = 1200;
        /**
         * 多意图综合回答最大输出 token
         */
        private int multiIntentMaxTokens = 900;
        /**
         * 多意图场景下每个子问题最多附带的来源数
         */
        private int multiIntentSourcesPerQuestion = 2;
        /**
         * 多意图场景下每条来源的最大字符数
         */
        private int multiIntentSnippetLength = 120;
        /**
         * 多意图综合时是否拼接历史消息
         */
        private boolean includeHistoryInMultiIntent = false;
        /**
         * 是否启用多意图结构化直返快速路径
         */
        private boolean multiIntentStructuredFastPathEnabled = true;
        /**
         * 触发多意图结构化直返时，允许的最多 RAG 子问题数量
         */
        private int multiIntentStructuredFastPathMaxRagCount = 1;
        /**
         * 当所有子问题都是工具/澄清结果时，是否直接模板化返回
         */
        private boolean multiIntentAllDirectTemplateEnabled = true;
    }
}
