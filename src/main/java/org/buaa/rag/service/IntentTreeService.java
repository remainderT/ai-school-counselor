package org.buaa.rag.service;

import java.util.List;
import java.util.Optional;

import org.buaa.rag.dto.IntentNode;

public interface IntentTreeService {

    /**
     * 获取根节点
     */
    Optional<IntentNode> root();

    /**
     * 根据ID获取节点
     */
    Optional<IntentNode> getById(String nodeId);

    /**
     * 获取子节点列表
     */
    List<IntentNode> children(String nodeId);

    /**
     * 预加载配置
     */
    void loadTree();
}
