package org.buaa.rag.core.online.tool.mcp;

import java.util.List;
import java.util.Map;

public record LocalMcpToolDefinition(
    String id,
    String description,
    Map<String, ParameterSpec> parameters
) {

    public record ParameterSpec(
        String type,
        String description,
        boolean required,
        Object defaultValue,
        List<String> enumValues
    ) {
    }
}
