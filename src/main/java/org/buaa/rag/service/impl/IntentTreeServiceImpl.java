package org.buaa.rag.service.impl;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.buaa.rag.dto.IntentNode;
import org.buaa.rag.service.IntentTreeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentTreeServiceImpl implements IntentTreeService {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    @Value("${intent.tree-path:classpath:intent-tree.json}")
    private String treePath;

    private final Map<String, IntentNode> nodes = new HashMap<>();
    private volatile boolean loaded;

    @Override
    public Optional<IntentNode> root() {
        ensureLoaded();
        return nodes.values().stream()
            .filter(n -> n.getParentId() == null || n.getParentId().isBlank())
            .findFirst();
    }

    @Override
    public Optional<IntentNode> getById(String nodeId) {
        ensureLoaded();
        return Optional.ofNullable(nodes.get(nodeId));
    }

    @Override
    public List<IntentNode> children(String nodeId) {
        ensureLoaded();
        return nodes.values().stream()
            .filter(n -> nodeId.equals(n.getParentId()))
            .collect(Collectors.toList());
    }

    @Override
    public synchronized void loadTree() {
        if (loaded && !nodes.isEmpty()) {
            return;
        }
        try {
            Resource resource = resourceLoader.getResource(treePath);
            try (InputStream is = resource.getInputStream()) {
                List<IntentNode> list = objectMapper.readValue(is, new TypeReference<>() { });
                nodes.clear();
                for (IntentNode node : list) {
                    nodes.put(node.getNodeId(), node);
                }
                loaded = true;
                log.info("加载意图树成功, 节点数: {}", nodes.size());
            }
        } catch (Exception e) {
            log.error("加载意图树失败: {}", e.getMessage());
            nodes.clear();
            loaded = false;
        }
    }

    private void ensureLoaded() {
        if (!loaded || nodes.isEmpty()) {
            loadTree();
        }
    }
}
