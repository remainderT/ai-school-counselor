package org.buaa.rag.dto.req;

import java.util.List;

import lombok.Data;

@Data
public class IntentNodeCreateReqDTO {

    private String nodeId;

    private String nodeName;

    private String parentId;

    private String nodeType;

    private String description;

    private String promptTemplate;

    private String promptSnippet;

    private String paramPromptTemplate;

    private List<String> keywords;

    private String knowledgeBaseId;

    private String actionService;

    private String nodeLevel;

    private String nodeKind;

    private String mcpToolId;

    private Integer topK;

    private Integer sortOrder;

    private Integer enabled;
}
