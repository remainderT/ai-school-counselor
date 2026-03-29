package org.buaa.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CRAG 决策结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CragDecision {

    private Action action;
    private String message;

    public enum Action {
        ANSWER,
        REFINE,
        CLARIFY,
        NO_ANSWER
    }
}
