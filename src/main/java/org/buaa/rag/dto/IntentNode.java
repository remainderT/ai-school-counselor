package org.buaa.rag.dto;

import java.util.List;
import lombok.Data;

/**
 * 配置化意图树节点
 */
@Data
public class IntentNode {

    public enum NodeType {
        GROUP,        // 目录
        RAG_QA,       // 知识库问答
        API_ACTION,   // 工具/API
        CHITCHAT      // 闲聊
    }

    private String nodeId;
    private String nodeName;
    private String parentId;
    private NodeType type;
    private String description;
    private String promptTemplate;
    private List<String> keywords;
    private String knowledgeBaseId;
    private String actionService;
    private List<String> children;
}
