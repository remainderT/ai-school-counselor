package org.buaa.rag.core.online.mcp;

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
            "学期编码，例如 2022-2023-2。用户也可能说“2023学年春季”“这学期”“本学期”“当前学期”“上学期”“下学期”。若为空，则默认查询当前选中学期。",
            false,
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
            "查询课表；可按指定学期和周次查，也可在未指定学期时默认查询当前选中学期",
            parameters
        );
    }

    @Override
    public String execute(Map<String, Object> parameters) {
        Integer week = parameters.get("week") instanceof Number number ? number.intValue() : null;
        return academicAffairsTools.queryScheduleByWeek((String) parameters.get("termCode"), week);
    }
}
