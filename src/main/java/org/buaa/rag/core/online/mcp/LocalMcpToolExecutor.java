package org.buaa.rag.core.online.mcp;

import java.util.Map;

public interface LocalMcpToolExecutor {

    String getToolId();

    LocalMcpToolDefinition getToolDefinition();

    String execute(Map<String, Object> parameters);
}
