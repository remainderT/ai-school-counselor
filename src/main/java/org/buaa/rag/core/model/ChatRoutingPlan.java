package org.buaa.rag.core.model;

import java.util.List;

import org.buaa.rag.core.online.intent.SubQueryIntent;

public record ChatRoutingPlan(String rewrittenQuery,
                              List<SubQueryIntent> subQueryIntents,
                              long rewriteLatencyMs) {
}
