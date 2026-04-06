package org.buaa.rag.core.online.rewrite;

import java.util.List;

/**
 * 查询改写+拆分的结果
 *
 * @param rewrittenQuery 改写后的主问题（用于检索和路由）
 * @param subQuestions   拆分出的子问题列表；单意图时为空列表
 * @param latencyMs      本次 LLM 调用耗时（毫秒）
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
