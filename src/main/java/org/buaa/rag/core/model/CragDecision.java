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
        /** 检索文档质量足够，放行进入 Prompt 生成（对应 LLM verdict: CORRECT） */
        CORRECT,
        /** 检索文档与问题不相关，直接拒答（对应 LLM verdict: INCORRECT） */
        INCORRECT,
        /** 问题意图模糊，向用户发起澄清引导（对应 LLM verdict: AMBIGUOUS） */
        AMBIGUOUS
    }
}
