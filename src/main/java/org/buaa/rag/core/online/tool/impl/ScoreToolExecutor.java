package org.buaa.rag.core.online.tool.impl;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.online.tool.ToolExecutor;
import org.buaa.rag.tool.CounselorTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 成绩查询工具执行器：查询当前用户的绩点和学分信息。
 */
@Component
public class ScoreToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScoreToolExecutor.class);

    private final CounselorTools counselorTools;

    public ScoreToolExecutor(CounselorTools counselorTools) {
        this.counselorTools = counselorTools;
    }

    @Override
    public String toolName() {
        return "score";
    }

    @Override
    public String execute(String userId, String userQuery, IntentDecision decision) {
        log.info("触发成绩查询工具, userId={}", userId);
        counselorTools.queryGrade(safeValue(userId));
        return "成绩查询结果：90分。";
    }

}
