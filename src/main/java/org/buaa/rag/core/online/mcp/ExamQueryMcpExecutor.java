package org.buaa.rag.core.online.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.tool.AcademicAffairsTools;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ExamQueryMcpExecutor implements LocalMcpToolExecutor {

    public static final String TOOL_ID = "academic_exam_query";

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
            "学期编码，例如 2024-2025-1。用户也可能说“这学期”“上学期”“2024学年秋季”。若为空，则自动遍历全部学期查询。",
            false,
            null,
            List.of()
        ));
        parameters.put("courseName", new LocalMcpToolDefinition.ParameterSpec(
            "string",
            "课程名关键字，例如 编译技术、数学分析。若为空，则返回对应学期全部考试安排。",
            false,
            null,
            List.of()
        ));
        return new LocalMcpToolDefinition(
            TOOL_ID,
            "查询考试安排；可按单学期查，也可在未指定学期时自动遍历全部学期，并支持按课程名筛选",
            parameters
        );
    }

    @Override
    public String execute(Map<String, Object> parameters) {
        return academicAffairsTools.queryExams(
            (String) parameters.get("termCode"),
            (String) parameters.get("courseName")
        );
    }
}
