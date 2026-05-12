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
            "学期编码，例如 2021-2022-1。用户也可能说“2022学年春季”“这学期”“本学期”“当前学期”“上学期”“下学期”。若为空，则自动遍历全部学期查询。",
            false,
            null,
            List.of()
        ));
        parameters.put("courseName", new LocalMcpToolDefinition.ParameterSpec(
            "string",
            "课程名关键字，例如 数学分析、编译技术。若为空，则返回对应学期全部成绩。",
            false,
            null,
            List.of()
        ));
        return new LocalMcpToolDefinition(
            TOOL_ID,
            "查询成绩；可按单学期查，也可在未指定学期时自动遍历全部学期，并支持按课程名筛选",
            parameters
        );
    }

    @Override
    public String execute(Map<String, Object> parameters) {
        return academicAffairsTools.queryScores(
            (String) parameters.get("termCode"),
            (String) parameters.get("courseName")
        );
    }
}
