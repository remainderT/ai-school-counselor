package org.buaa.rag.core.online.tool.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMcpToolRegistry {

    private final List<LocalMcpToolExecutor> executors;

    private final Map<String, LocalMcpToolExecutor> executorMap = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        for (LocalMcpToolExecutor executor : executors) {
            executorMap.put(executor.getToolId(), executor);
        }
        log.info("本地 MCP 工具注册完成, 共 {} 个", executorMap.size());
    }

    public Optional<LocalMcpToolExecutor> getExecutor(String toolId) {
        return Optional.ofNullable(executorMap.get(toolId));
    }
}
