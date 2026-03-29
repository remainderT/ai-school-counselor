package org.buaa.rag.core.online.intent;

import java.util.List;

import org.buaa.rag.core.model.IntentDecision;

/**
 * 子问题意图候选
 */
public record SubQueryIntent(String subQuery, List<IntentDecision> candidates) {
}
