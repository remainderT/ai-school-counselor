package org.buaa.rag.core.online.tool.impl;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.online.tool.ToolExecutor;
import org.buaa.rag.tool.CounselorTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 请假工具执行器：创建请假草稿并返回后续操作引导。
 */
@Component
public class LeaveToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(LeaveToolExecutor.class);

    private final CounselorTools counselorTools;

    public LeaveToolExecutor(CounselorTools counselorTools) {
        this.counselorTools = counselorTools;
    }

    @Override
    public String toolName() {
        return "leave";
    }

    @Override
    public String execute(String userId, String userQuery, IntentDecision decision) {
        log.info("触发请假工具, userId={}, query={}", userId, userQuery);
        CounselorTools.LeaveDraftToolResult result = counselorTools.createLeaveDraft(
            safeValue(userId), null, null, userQuery);
        return "已创建请假草稿，状态：" + result.status()
            + "；后续操作：" + result.nextAction();
    }

}
