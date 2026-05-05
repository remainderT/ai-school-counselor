package org.buaa.rag.core.online.intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.buaa.rag.common.consts.CacheConstants;
import org.buaa.rag.dao.entity.IntentNodeDO;
import org.buaa.rag.dao.mapper.IntentNodeMapper;
import org.buaa.rag.core.model.IntentNode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentTreeSnapshotService {

    private final ObjectMapper objectMapper;
    private final IntentNodeMapper intentNodeMapper;
    private final StringRedisTemplate stringRedisTemplate;

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
            List<IntentNode> list = loadFromCache();
            if (list == null || list.isEmpty()) {
                list = loadFromDatabase();
                if (list != null && !list.isEmpty()) {
                    cacheTree(list);
                }
            }
            snapshot = buildSnapshot(list);
            loaded = true;
            log.info("加载意图树成功, 节点数: {}", snapshot.nodes().size());
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

    public synchronized void refreshTreeSnapshot() {
        snapshot = TreeSnapshot.empty();
        loaded = false;
        clearCache();
        loadTree();
    }

    private List<IntentNode> loadFromCache() {
        try {
            String cacheVal = stringRedisTemplate.opsForValue().get(CacheConstants.INTENT_TREE_KEY);
            if (!StringUtils.hasText(cacheVal)) {
                return null;
            }
            List<IntentNode> cached = objectMapper.readValue(cacheVal, new TypeReference<List<IntentNode>>() {
            });
            if (cached != null && !cached.isEmpty()) {
                log.info("从缓存加载意图树成功, 节点数: {}", cached.size());
            }
            return cached;
        } catch (Exception e) {
            log.warn("从缓存加载意图树失败: {}", e.getMessage());
            return null;
        }
    }

    private List<IntentNode> loadFromDatabase() {
        List<IntentNodeDO> list = intentNodeMapper.selectList(
            Wrappers.lambdaQuery(IntentNodeDO.class)
                .eq(IntentNodeDO::getDelFlag, 0)
                .eq(IntentNodeDO::getEnabled, 1)
                .orderByAsc(IntentNodeDO::getCreateTime, IntentNodeDO::getId)
        );
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<IntentNode> nodes = new ArrayList<>(list.size());
        for (IntentNodeDO node : list) {
            IntentNode mapped = toIntentNode(node);
            if (mapped != null) {
                nodes.add(mapped);
            }
        }
        if (!nodes.isEmpty()) {
            log.info("从数据库加载意图树成功, 节点数: {}", nodes.size());
        }
        return nodes;
    }

    private IntentNode toIntentNode(IntentNodeDO node) {
        if (node == null || !StringUtils.hasText(node.getNodeId())) {
            return null;
        }
        IntentNode result = new IntentNode();
        result.setNodeId(node.getNodeId().trim());
        result.setNodeName(node.getNodeName());
        result.setParentId(StringUtils.hasText(node.getParentId()) ? node.getParentId().trim() : null);
        result.setDescription(node.getDescription());
        result.setPromptTemplate(node.getPromptTemplate());
        result.setPromptSnippet(node.getPromptSnippet());
        result.setParamPromptTemplate(node.getParamPromptTemplate());
        result.setKnowledgeBaseId(node.getKnowledgeBaseId());
        result.setActionService(node.getActionService());
        result.setMcpToolId(node.getMcpToolId());
        result.setTopK(node.getTopK());
        result.setKeywords(parseKeywords(node.getKeywordsJson()));

        IntentNode.NodeType type = parseNodeType(node.getNodeType());
        result.setType(type == null ? IntentNode.NodeType.GROUP : type);
        return result;
    }

    private IntentNode.NodeType parseNodeType(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return null;
        }
        try {
            return IntentNode.NodeType.valueOf(nodeType.trim().toUpperCase());
        } catch (Exception ignore) {
            return null;
        }
    }

    private List<String> parseKeywords(String keywordsJson) {
        if (!StringUtils.hasText(keywordsJson)) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(keywordsJson, new TypeReference<List<String>>() {
            });
            if (list == null) {
                return List.of();
            }
            return list.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private void cacheTree(List<IntentNode> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        try {
            String content = objectMapper.writeValueAsString(list);
            stringRedisTemplate.opsForValue().set(
                CacheConstants.INTENT_TREE_KEY,
                content,
                CacheConstants.INTENT_TREE_EXPIRE_DAYS,
                java.util.concurrent.TimeUnit.DAYS
            );
        } catch (Exception e) {
            log.warn("写入意图树缓存失败: {}", e.getMessage());
        }
    }

    private void clearCache() {
        try {
            stringRedisTemplate.delete(CacheConstants.INTENT_TREE_KEY);
        } catch (Exception e) {
            log.warn("清理意图树缓存失败: {}", e.getMessage());
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

        // 预计算叶子节点列表，避免每次请求做全树 DFS
        List<IntentNode> leafNodes = nodeMap.values().stream()
            .filter(node -> {
                List<IntentNode> ch = childrenMap.get(node.getNodeId());
                return ch == null || ch.isEmpty();
            })
            .filter(node -> !"root".equalsIgnoreCase(node.getNodeId()))
            .toList();

        return new TreeSnapshot(
            Collections.unmodifiableMap(new LinkedHashMap<>(nodeMap)),
            Collections.unmodifiableMap(immutableChildren),
            root,
            List.copyOf(leafNodes)
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

    /**
     * 返回意图树所有叶子节点（即无子节点的节点）。
     *
     * <p>叶子列表在 {@link #buildSnapshot} 时一次性计算并缓存，
     * 后续所有调用（{@code IntentRouterService.routeByTree}、
     * {@code IntentRouterService.rankIntentCandidates}）直接复用，
     * 避免每次请求都做全树 DFS 遍历。
     */
    public List<IntentNode> leaves() {
        ensureLoaded();
        return snapshot.leafNodes();
    }

    private record TreeSnapshot(Map<String, IntentNode> nodes,
                                Map<String, List<IntentNode>> children,
                                IntentNode root,
                                List<IntentNode> leafNodes) {
        private static TreeSnapshot empty() {
            return new TreeSnapshot(Map.of(), Map.of(), null, List.of());
        }
    }
}
