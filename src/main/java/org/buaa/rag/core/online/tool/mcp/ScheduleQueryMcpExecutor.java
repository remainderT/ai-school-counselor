package org.buaa.rag.core.online.tool.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.tool.AcademicAffairsTools;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ScheduleQueryMcpExecutor implements LocalMcpToolExecutor {

    public static final String TOOL_ID = "academic_schedule_query";

    private final AcademicAffairsTools academicAffairsTools;

    @Override
    public String getToolId() {
        return TOOL_ID;
    }

    @Override
    public LocalMcpToolDefinition getToolDefinition() {
        Map<String, LocalMcpToolDefinition.ParameterSpec> parameters = new LinkedHashMap<>();
        parameters.put("termCode", new LocalMcpToolDefinition.ParameterSpec(
            "string",
            "学期编码，例如 2022-2023-2。也支持从“2023学年春季”自动换算。",
            true,
            null,
            List.of()
        ));
        parameters.put("week", new LocalMcpToolDefinition.ParameterSpec(
            "integer",
            "周次，默认第 1 周",
            false,
            1,
            List.of()
        ));
        return new LocalMcpToolDefinition(
            TOOL_ID,
            "查询指定学期、指定周次的已排课和未排定时间课程",
            parameters
        );
    }

    @Override
    public String execute(Map<String, Object> parameters) {
        Integer week = parameters.get("week") instanceof Number number ? number.intValue() : null;
        return academicAffairsTools.queryScheduleByWeek((String) parameters.get("termCode"), week);
    }
}
