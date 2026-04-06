package org.buaa.rag.core.online.tool.impl;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.online.tool.ToolExecutor;
import org.buaa.rag.tool.CounselorTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 报修工具执行器：创建报修工单草稿并返回后续操作引导。
 */
@Component
public class RepairToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(RepairToolExecutor.class);

    private final CounselorTools counselorTools;

    public RepairToolExecutor(CounselorTools counselorTools) {
        this.counselorTools = counselorTools;
    }

    @Override
    public String toolName() {
        return "repair";
    }

    @Override
    public String execute(String userId, String userQuery, IntentDecision decision) {
        log.info("触发报修工具, userId={}, query={}", userId, userQuery);
        CounselorTools.RepairDraftToolResult result = counselorTools.createRepairTicket(
            safeValue(userId), null, userQuery);
        return "已创建报修草稿，状态：" + result.status()
            + "；后续操作：" + result.nextAction();
    }

}
