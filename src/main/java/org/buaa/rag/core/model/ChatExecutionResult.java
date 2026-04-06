package org.buaa.rag.core.model;

import java.util.List;

public record ChatExecutionResult(String response,
                                  List<RetrievalMatch> sources,
                                  boolean clarifyTriggered,
                                  int retrievedCount,
                                  int retrievalTopK,
                                  long rewriteLatencyMs) {

    /**
     * 歧义引导场景快捷工厂：无检索结果，直接回复引导语。
     */
    public static ChatExecutionResult ofGuidance(String guidanceResponse, long rewriteLatencyMs) {
        return new ChatExecutionResult(guidanceResponse, List.of(), true, 0, 0, rewriteLatencyMs);
    }

    /**
     * 错误场景快捷工厂。
     */
    public static ChatExecutionResult ofError(String errorMessage) {
        return new ChatExecutionResult(errorMessage, List.of(), false, 0, 0, 0L);
    }
}
