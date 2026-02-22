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
}
