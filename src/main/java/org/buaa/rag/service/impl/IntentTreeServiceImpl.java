package org.buaa.rag.service.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.dao.entity.IntentNodeDO;
import org.buaa.rag.dao.mapper.IntentNodeMapper;
import org.buaa.rag.core.model.IntentNode;
import org.buaa.rag.dto.req.IntentNodeCreateReqDTO;
import org.buaa.rag.dto.req.IntentNodeUpdateReqDTO;
import org.buaa.rag.dto.resp.IntentNodeTreeRespDTO;
import org.buaa.rag.service.IntentTreeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class IntentTreeServiceImpl implements IntentTreeService {

    private final IntentNodeMapper intentNodeMapper;
    private final org.buaa.rag.core.online.intent.IntentTreeService intentTreeService;
    private final ObjectMapper objectMapper;

    @Override
    public List<IntentNodeTreeRespDTO> tree() {
        List<IntentNodeDO> nodes = listActiveNodes();
        if (nodes.isEmpty()) {
            return List.of();
        }
        Map<String, List<IntentNodeDO>> childrenMap = buildChildrenMap(nodes);
        List<IntentNodeDO> roots = childrenMap.getOrDefault("__ROOT__", List.of());
        List<IntentNodeTreeRespDTO> result = new ArrayList<>(roots.size());
        for (IntentNodeDO root : roots) {
            result.add(toTree(root, childrenMap));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(IntentNodeCreateReqDTO requestParam) {
        if (requestParam == null) {
            throw new ClientException("请求不能为空");
        }
        String nodeId = requireText(requestParam.getNodeId(), "nodeId 不能为空");
        if (existsNodeId(nodeId, null)) {
            throw new ClientException("nodeId 已存在: " + nodeId);
        }

        String normalizedType = normalizeNodeType(requestParam.getNodeType());
        validateParent(nodeId, requestParam.getParentId(), null);
        validateNodeTypeAndFields(normalizedType, requestParam.getKnowledgeBaseId(), requestParam.getActionService());

        IntentNodeDO entity = IntentNodeDO.builder()
            .nodeId(nodeId)
            .nodeName(requireText(requestParam.getNodeName(), "nodeName 不能为空"))
            .parentId(normalizeNullable(requestParam.getParentId()))
            .nodeType(normalizedType)
            .description(normalizeNullable(requestParam.getDescription()))
            .promptTemplate(normalizeNullable(requestParam.getPromptTemplate()))
            .promptSnippet(normalizeNullable(requestParam.getPromptSnippet()))
            .paramPromptTemplate(normalizeNullable(requestParam.getParamPromptTemplate()))
            .keywordsJson(toJsonArray(requestParam.getKeywords()))
            .knowledgeBaseId(requestParam.getKnowledgeBaseId())
            .actionService(normalizeNullable(requestParam.getActionService()))
            .mcpToolId(normalizeNullable(requestParam.getMcpToolId()))
            .topK(requestParam.getTopK())
            .enabled(requestParam.getEnabled() == null ? 1 : normalizeEnabled(requestParam.getEnabled()))
            .build();
        intentNodeMapper.insert(entity);
        intentTreeService.refreshTreeSnapshot();
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, IntentNodeUpdateReqDTO requestParam) {
        if (id == null) {
            throw new ClientException("id 不能为空");
        }
        if (requestParam == null) {
            throw new ClientException("请求不能为空");
        }

        IntentNodeDO existing = getActiveById(id);
        String nextType = requestParam.getNodeType() == null
            ? existing.getNodeType()
            : normalizeNodeType(requestParam.getNodeType());
        String nextParentId = requestParam.getParentId() == null
            ? existing.getParentId()
            : normalizeNullable(requestParam.getParentId());
        Long nextKnowledgeBaseId = requestParam.getKnowledgeBaseId() == null
            ? existing.getKnowledgeBaseId()
            : requestParam.getKnowledgeBaseId();
        String nextActionService = requestParam.getActionService() == null
            ? existing.getActionService()
            : normalizeNullable(requestParam.getActionService());

        validateParent(existing.getNodeId(), nextParentId, existing.getId());
        validateNodeTypeAndFields(nextType, nextKnowledgeBaseId, nextActionService);

        if (requestParam.getNodeName() != null) {
            existing.setNodeName(requireText(requestParam.getNodeName(), "nodeName 不能为空"));
        }
        if (requestParam.getParentId() != null) {
            existing.setParentId(nextParentId);
        }
        if (requestParam.getNodeType() != null) {
            existing.setNodeType(nextType);
        }
        if (requestParam.getDescription() != null) {
            existing.setDescription(normalizeNullable(requestParam.getDescription()));
        }
        if (requestParam.getPromptTemplate() != null) {
            existing.setPromptTemplate(normalizeNullable(requestParam.getPromptTemplate()));
        }
        if (requestParam.getPromptSnippet() != null) {
            existing.setPromptSnippet(normalizeNullable(requestParam.getPromptSnippet()));
        }
        if (requestParam.getParamPromptTemplate() != null) {
            existing.setParamPromptTemplate(normalizeNullable(requestParam.getParamPromptTemplate()));
        }
        if (requestParam.getKeywords() != null) {
            existing.setKeywordsJson(toJsonArray(requestParam.getKeywords()));
        }
        if (requestParam.getKnowledgeBaseId() != null) {
            existing.setKnowledgeBaseId(nextKnowledgeBaseId);
        }
        if (requestParam.getActionService() != null) {
            existing.setActionService(nextActionService);
        }
        if (requestParam.getMcpToolId() != null) {
            existing.setMcpToolId(normalizeNullable(requestParam.getMcpToolId()));
        }
        if (requestParam.getTopK() != null) {
            existing.setTopK(requestParam.getTopK());
        }
        if (requestParam.getEnabled() != null) {
            existing.setEnabled(normalizeEnabled(requestParam.getEnabled()));
        }
        intentNodeMapper.updateById(existing);
        intentTreeService.refreshTreeSnapshot();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        IntentNodeDO existing = getActiveById(id);
        List<IntentNodeDO> active = listActiveNodes();
        Map<String, List<IntentNodeDO>> childrenMap = buildChildrenMap(active);
        List<Long> deleteIds = collectDescendantIds(existing.getNodeId(), childrenMap).stream()
            .map(IntentNodeDO::getId)
            .collect(Collectors.toCollection(ArrayList::new));
        deleteIds.add(existing.getId());
        if (!deleteIds.isEmpty()) {
            intentNodeMapper.deleteBatchIds(deleteIds);
        }
        intentTreeService.refreshTreeSnapshot();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchEnable(List<Long> ids) {
        batchSetEnabled(ids, 1);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDisable(List<Long> ids) {
        batchSetEnabled(ids, 0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> ids) {
        List<IntentNodeDO> targets = listActiveByIds(ids);
        if (targets.isEmpty()) {
            return;
        }
        Map<String, List<IntentNodeDO>> childrenMap = buildChildrenMap(listActiveNodes());
        Set<Long> allIds = targets.stream().map(IntentNodeDO::getId).collect(Collectors.toSet());
        for (IntentNodeDO node : targets) {
            for (IntentNodeDO descendant : collectDescendantIds(node.getNodeId(), childrenMap)) {
                allIds.add(descendant.getId());
            }
        }
        intentNodeMapper.deleteBatchIds(allIds);
        intentTreeService.refreshTreeSnapshot();
    }

    private void batchSetEnabled(List<Long> ids, int enabled) {
        List<IntentNodeDO> targets = listActiveByIds(ids);
        if (targets.isEmpty()) {
            return;
        }
        for (IntentNodeDO target : targets) {
            target.setEnabled(enabled);
            intentNodeMapper.updateById(target);
        }
        intentTreeService.refreshTreeSnapshot();
    }

    private IntentNodeDO getActiveById(Long id) {
        IntentNodeDO node = intentNodeMapper.selectOne(
            Wrappers.lambdaQuery(IntentNodeDO.class)
                .eq(IntentNodeDO::getId, id)
                .eq(IntentNodeDO::getDelFlag, 0)
        );
        if (node == null) {
            throw new ClientException("意图节点不存在: " + id);
        }
        return node;
    }

    private List<IntentNodeDO> listActiveNodes() {
        return intentNodeMapper.selectList(
            Wrappers.lambdaQuery(IntentNodeDO.class)
                .eq(IntentNodeDO::getDelFlag, 0)
                .orderByAsc(IntentNodeDO::getCreateTime, IntentNodeDO::getId)
        );
    }

    private List<IntentNodeDO> listActiveByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return intentNodeMapper.selectList(
            Wrappers.lambdaQuery(IntentNodeDO.class)
                .in(IntentNodeDO::getId, ids)
                .eq(IntentNodeDO::getDelFlag, 0)
        );
    }

    private boolean existsNodeId(String nodeId, Long excludeId) {
        var query = Wrappers.lambdaQuery(IntentNodeDO.class)
            .eq(IntentNodeDO::getNodeId, nodeId)
            .eq(IntentNodeDO::getDelFlag, 0);
        if (excludeId != null) {
            query.ne(IntentNodeDO::getId, excludeId);
        }
        Long count = intentNodeMapper.selectCount(query);
        return count != null && count > 0;
    }

    private void validateParent(String nodeId, String parentId, Long selfId) {
        if (!StringUtils.hasText(parentId)) {
            return;
        }
        if (Objects.equals(nodeId, parentId)) {
            throw new ClientException("父节点不能是自己");
        }

        IntentNodeDO parent = intentNodeMapper.selectOne(
            Wrappers.lambdaQuery(IntentNodeDO.class)
                .eq(IntentNodeDO::getNodeId, parentId)
                .eq(IntentNodeDO::getDelFlag, 0)
                .last("limit 1")
        );
        if (parent == null) {
            throw new ClientException("父节点不存在: " + parentId);
        }

        // 防止形成环：沿父链向上不能遇到当前 nodeId
        String cursor = parent.getParentId();
        int guard = 0;
        while (StringUtils.hasText(cursor) && guard++ < 100) {
            if (Objects.equals(cursor, nodeId)) {
                throw new ClientException("父子关系非法：会形成循环");
            }
            IntentNodeDO upper = intentNodeMapper.selectOne(
                Wrappers.lambdaQuery(IntentNodeDO.class)
                    .eq(IntentNodeDO::getNodeId, cursor)
                    .eq(IntentNodeDO::getDelFlag, 0)
                    .last("limit 1")
            );
            if (upper == null) {
                break;
            }
            if (selfId != null && Objects.equals(upper.getId(), selfId)) {
                throw new ClientException("父子关系非法：会形成循环");
            }
            cursor = upper.getParentId();
        }
    }

    private void validateNodeTypeAndFields(String nodeType, Long knowledgeBaseId, String actionService) {
        if (IntentNode.NodeType.RAG_QA.name().equals(nodeType) && knowledgeBaseId == null) {
            throw new ClientException("RAG_QA 节点必须指定 knowledgeBaseId");
        }
        if (IntentNode.NodeType.API_ACTION.name().equals(nodeType) && !StringUtils.hasText(actionService)) {
            throw new ClientException("API_ACTION 节点必须指定 actionService");
        }
    }

    private String normalizeNodeType(String nodeType) {
        String normalized = requireText(nodeType, "nodeType 不能为空").toUpperCase();
        try {
            IntentNode.NodeType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new ClientException("不支持的 nodeType: " + nodeType);
        }
        return normalized;
    }

    private Integer normalizeEnabled(Integer enabled) {
        if (enabled == null) {
            return 1;
        }
        if (enabled == 0 || enabled == 1) {
            return enabled;
        }
        throw new ClientException("enabled 只允许 0 或 1");
    }

    private String requireText(String text, String message) {
        if (!StringUtils.hasText(text)) {
            throw new ClientException(message);
        }
        return text.trim();
    }

    private String normalizeNullable(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return text.trim();
    }

    private String toJsonArray(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return "[]";
        }
        List<String> normalized = keywords.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .toList();
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new ClientException("keywords 序列化失败");
        }
    }

    private List<String> parseKeywords(String keywordsJson) {
        if (!StringUtils.hasText(keywordsJson)) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(keywordsJson, new TypeReference<List<String>>() {
            });
            if (parsed == null) {
                return List.of();
            }
            return parsed.stream().filter(StringUtils::hasText).map(String::trim).toList();
        } catch (Exception ignore) {
            return List.of();
        }
    }

    private Map<String, List<IntentNodeDO>> buildChildrenMap(List<IntentNodeDO> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Map.of();
        }
        Map<String, List<IntentNodeDO>> childrenMap = new HashMap<>();
        for (IntentNodeDO node : nodes) {
            String parent = StringUtils.hasText(node.getParentId()) ? node.getParentId().trim() : "__ROOT__";
            childrenMap.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(node);
        }
        for (List<IntentNodeDO> children : childrenMap.values()) {
            children.sort((a, b) -> {
                int timeCompare = compareDate(a.getCreateTime(), b.getCreateTime());
                if (timeCompare != 0) {
                    return timeCompare;
                }
                long idA = a.getId() == null ? 0L : a.getId();
                long idB = b.getId() == null ? 0L : b.getId();
                return Long.compare(idA, idB);
            });
        }
        return childrenMap;
    }

    private List<IntentNodeDO> collectDescendantIds(String nodeId, Map<String, List<IntentNodeDO>> childrenMap) {
        if (!StringUtils.hasText(nodeId) || childrenMap.isEmpty()) {
            return List.of();
        }
        List<IntentNodeDO> result = new ArrayList<>();
        Deque<IntentNodeDO> stack = new ArrayDeque<>(childrenMap.getOrDefault(nodeId, Collections.emptyList()));
        while (!stack.isEmpty()) {
            IntentNodeDO current = stack.pop();
            result.add(current);
            List<IntentNodeDO> children = childrenMap.getOrDefault(current.getNodeId(), Collections.emptyList());
            for (IntentNodeDO child : children) {
                stack.push(child);
            }
        }
        return result;
    }

    private IntentNodeTreeRespDTO toTree(IntentNodeDO node, Map<String, List<IntentNodeDO>> childrenMap) {
        IntentNodeTreeRespDTO dto = new IntentNodeTreeRespDTO();
        dto.setId(node.getId());
        dto.setNodeId(node.getNodeId());
        dto.setNodeName(node.getNodeName());
        dto.setParentId(node.getParentId());
        dto.setNodeType(node.getNodeType());
        dto.setDescription(node.getDescription());
        dto.setPromptTemplate(node.getPromptTemplate());
        dto.setPromptSnippet(node.getPromptSnippet());
        dto.setParamPromptTemplate(node.getParamPromptTemplate());
        dto.setKeywords(parseKeywords(node.getKeywordsJson()));
        dto.setKnowledgeBaseId(node.getKnowledgeBaseId());
        dto.setActionService(node.getActionService());
        dto.setMcpToolId(node.getMcpToolId());
        dto.setTopK(node.getTopK());
        dto.setEnabled(node.getEnabled());

        List<IntentNodeDO> children = childrenMap.getOrDefault(node.getNodeId(), List.of());
        if (children.isEmpty()) {
            dto.setChildren(List.of());
        } else {
            List<IntentNodeTreeRespDTO> subTrees = new ArrayList<>(children.size());
            for (IntentNodeDO child : children) {
                subTrees.add(toTree(child, childrenMap));
            }
            dto.setChildren(subTrees);
        }
        return dto;
    }

    private int compareDate(Date left, Date right) {
        long leftTs = left == null ? 0L : left.getTime();
        long rightTs = right == null ? 0L : right.getTime();
        return Long.compare(leftTs, rightTs);
    }
}
