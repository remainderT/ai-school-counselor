package org.buaa.rag.core.model;

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
    private String promptSnippet;
    private String paramPromptTemplate;
    private List<String> keywords;
    private Long knowledgeBaseId;
    private String actionService;
    private String mcpToolId;
    private Integer topK;
    private List<String> children;
}
