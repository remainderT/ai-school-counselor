package org.buaa.rag.core.online.tool.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AcademicMcpParameterExtractor {

    private final BuaaAcademicTermResolver termResolver;

    public Map<String, Object> extractParameters(String userQuery,
                                                 LocalMcpToolDefinition toolDefinition,
                                                 String customPromptTemplate) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (toolDefinition == null) {
            return params;
        }
        String termCode = termResolver.resolveTermCode(userQuery);
        if (StringUtils.hasText(termCode)) {
            params.put("termCode", termCode);
        }
        Integer week = termResolver.resolveWeek(userQuery);
        if (week != null) {
            params.put("week", week);
        }
        toolDefinition.parameters().forEach((name, spec) -> {
            if (!params.containsKey(name) && spec != null && spec.defaultValue() != null) {
                params.put(name, spec.defaultValue());
            }
        });
        return params;
    }
}
