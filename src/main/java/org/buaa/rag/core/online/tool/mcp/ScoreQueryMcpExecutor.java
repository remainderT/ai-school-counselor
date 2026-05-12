package org.buaa.rag.core.online.tool.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.tool.AcademicAffairsTools;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ScoreQueryMcpExecutor implements LocalMcpToolExecutor {

    public static final String TOOL_ID = "academic_score_query";

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
            "学期编码，例如 2021-2022-1。用户也可能说“2022学年春季”“这学期”“本学期”“当前学期”“上学期”“下学期”，需要结合当前日期换算成标准 termCode。",
            true,
            null,
            List.of()
        ));
        return new LocalMcpToolDefinition(
            TOOL_ID,
            "查询指定学期的成绩明细、通过情况、均分和学分加权结果",
            parameters
        );
    }

    @Override
    public String execute(Map<String, Object> parameters) {
        return academicAffairsTools.queryScoresByTerm((String) parameters.get("termCode"));
    }
}
