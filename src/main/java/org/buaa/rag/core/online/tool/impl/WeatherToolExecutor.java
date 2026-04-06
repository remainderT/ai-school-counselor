package org.buaa.rag.core.online.tool.impl;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.online.tool.ToolExecutor;
import org.buaa.rag.tool.CounselorTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 天气查询工具执行器：通过 MCP 获取实时天气信息。
 */
@Component
public class WeatherToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(WeatherToolExecutor.class);

    private final CounselorTools counselorTools;

    public WeatherToolExecutor(CounselorTools counselorTools) {
        this.counselorTools = counselorTools;
    }

    @Override
    public String toolName() {
        return "weather";
    }

    @Override
    public String execute(String userId, String userQuery, IntentDecision decision) {
        log.info("触发天气查询工具, userId={}, query={}", userId, userQuery);
        CounselorTools.WeatherToolResult result = counselorTools.queryWeatherByMcp(
            null, "current", 3, safeValue(userId), userQuery);

        if ("NEED_CITY".equals(result.status()) || "UNAVAILABLE".equals(result.status())) {
            return result.summary();
        }
        String source = (result.source() == null || result.source().isBlank()) ? "unknown" : result.source();
        return result.summary() + "\n（天气数据来源: " + source + "）";
    }

}
