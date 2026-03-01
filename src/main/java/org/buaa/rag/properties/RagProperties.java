package org.buaa.rag.properties;

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

    private Rewrite rewrite = new Rewrite();
    private Hyde hyde = new Hyde();
    private Fusion fusion = new Fusion();
    private Rerank rerank = new Rerank();
    private Crag crag = new Crag();
    private Feedback feedback = new Feedback();
    private Decomposition decomposition = new Decomposition();
    private SemanticCache semanticCache = new SemanticCache();
    private Memory memory = new Memory();

    @Data
    public static class Rewrite {
        private boolean enabled = true;
        private int variants = 3;
        private String prompt;
    }

    @Data
    public static class Hyde {
        private boolean enabled = false;
        private int maxTokens = 256;
        private String prompt;
    }

    @Data
    public static class Fusion {
        private boolean enabled = true;
        private int rrfK = 60;
        private int maxQueries = 4;
    }

    @Data
    public static class Rerank {
        private boolean enabled = true;
        private int maxCandidates = 8;
        private int snippetLength = 200;
        private String prompt;
    }

    @Data
    public static class Crag {
        private boolean enabled = true;
        private boolean useLlm = true;
        private double minScore = 0.2;
        private int reviewTopK = 3;
        private int fallbackMultiplier = 2;
        private String prompt;
        private String clarifyPrompt;
    }

    @Data
    public static class Feedback {
        private boolean enabled = true;
        private double maxBoost = 0.15;
    }

    @Data
    public static class Decomposition {
        private boolean enabled = true;
        private int maxSubqueries = 3;
        private String prompt;
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
    }
}
