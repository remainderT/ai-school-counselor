package org.buaa.rag.module.intent;

import java.util.List;

import org.buaa.rag.dto.IntentDecision;

/**
 * 子问题意图候选
 */
public record SubQueryIntent(String subQuery, List<IntentDecision> candidates) {
}
