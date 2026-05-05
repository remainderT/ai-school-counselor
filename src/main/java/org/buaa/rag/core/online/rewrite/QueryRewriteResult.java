package org.buaa.rag.core.online.rewrite;

import java.util.List;

/**
 * 查询改写+拆分的结果
 */
public record QueryRewriteResult(String rewrittenQuery,
                                 List<String> subQuestions,
                                 long latencyMs) {

    /** 工作子问题列表：若拆分结果为空，退化为只含主问题的单元素列表 */
    public List<String> effectiveSubQuestions() {
        if (subQuestions == null || subQuestions.isEmpty()) {
            return List.of(rewrittenQuery);
        }
        return subQuestions;
    }
}
