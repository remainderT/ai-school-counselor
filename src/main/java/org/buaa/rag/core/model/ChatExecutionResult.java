package org.buaa.rag.core.model;

import java.util.List;

public record ChatExecutionResult(String response,
                                  List<RetrievalMatch> sources,
                                  boolean clarifyTriggered,
                                  int retrievedCount,
                                  int retrievalTopK,
                                  long rewriteLatencyMs) {
}
