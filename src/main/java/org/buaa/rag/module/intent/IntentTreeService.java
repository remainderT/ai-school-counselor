package org.buaa.rag.module.intent;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.buaa.rag.dto.IntentNode;
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
public class IntentTreeService {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    @Value("${intent.tree-path:classpath:intent-tree.json}")
    private String treePath;

    private volatile TreeSnapshot snapshot = TreeSnapshot.empty();
    private volatile boolean loaded;

    public Optional<IntentNode> root() {
        ensureLoaded();
        return Optional.ofNullable(snapshot.root());
    }

    public Optional<IntentNode> getById(String nodeId) {
        ensureLoaded();
        return Optional.ofNullable(snapshot.nodes().get(nodeId));
    }

    public List<IntentNode> children(String nodeId) {
        ensureLoaded();
        if (nodeId == null || nodeId.isBlank()) {
            return List.of();
        }
        return snapshot.children().getOrDefault(nodeId, List.of());
    }

    public synchronized void loadTree() {
        if (loaded && !snapshot.nodes().isEmpty()) {
            return;
        }
        try {
            Resource resource = resourceLoader.getResource(treePath);
            try (InputStream is = resource.getInputStream()) {
                List<IntentNode> list = objectMapper.readValue(is, new TypeReference<>() { });
                snapshot = buildSnapshot(list);
                loaded = true;
                log.info("加载意图树成功, 节点数: {}", snapshot.nodes().size());
            }
        } catch (Exception e) {
            log.error("加载意图树失败: {}", e.getMessage());
            snapshot = TreeSnapshot.empty();
            loaded = false;
        }
    }

    private void ensureLoaded() {
        if (!loaded || snapshot.nodes().isEmpty()) {
            loadTree();
        }
    }

    private TreeSnapshot buildSnapshot(List<IntentNode> list) {
        if (list == null || list.isEmpty()) {
            return TreeSnapshot.empty();
        }

        Map<String, IntentNode> nodeMap = new LinkedHashMap<>();
        for (IntentNode node : list) {
            if (node == null || node.getNodeId() == null || node.getNodeId().isBlank()) {
                continue;
            }
            if (nodeMap.containsKey(node.getNodeId())) {
                log.warn("意图树节点ID重复，已忽略后续节点: {}", node.getNodeId());
                continue;
            }
            nodeMap.put(node.getNodeId(), node);
        }

        Map<String, List<IntentNode>> childrenMap = new HashMap<>();
        List<IntentNode> rootCandidates = new ArrayList<>();

        for (IntentNode node : nodeMap.values()) {
            String parentId = normalizeParentId(node.getParentId());
            if (parentId == null) {
                rootCandidates.add(node);
                continue;
            }
            childrenMap.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(node);
        }

        for (List<IntentNode> children : childrenMap.values()) {
            children.sort((a, b) -> {
                String left = a.getNodeName() == null ? "" : a.getNodeName();
                String right = b.getNodeName() == null ? "" : b.getNodeName();
                return left.compareTo(right);
            });
        }

        IntentNode root = resolveRoot(nodeMap, rootCandidates);
        Map<String, List<IntentNode>> immutableChildren = new HashMap<>();
        for (Map.Entry<String, List<IntentNode>> entry : childrenMap.entrySet()) {
            immutableChildren.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new TreeSnapshot(
            Collections.unmodifiableMap(new LinkedHashMap<>(nodeMap)),
            Collections.unmodifiableMap(immutableChildren),
            root
        );
    }

    private IntentNode resolveRoot(Map<String, IntentNode> nodeMap, List<IntentNode> rootCandidates) {
        IntentNode root = nodeMap.get("root");
        if (root != null) {
            return root;
        }
        if (rootCandidates.isEmpty()) {
            return null;
        }
        if (rootCandidates.size() > 1) {
            log.warn("意图树检测到多个根节点，默认取第一个: {}", rootCandidates.get(0).getNodeId());
        }
        return rootCandidates.get(0);
    }

    private String normalizeParentId(String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return null;
        }
        return parentId.trim();
    }

    private record TreeSnapshot(Map<String, IntentNode> nodes,
                                Map<String, List<IntentNode>> children,
                                IntentNode root) {
        private static TreeSnapshot empty() {
            return new TreeSnapshot(Map.of(), Map.of(), null);
        }
    }
}
