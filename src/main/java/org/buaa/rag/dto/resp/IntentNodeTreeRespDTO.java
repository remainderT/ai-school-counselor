package org.buaa.rag.dto.resp;

import java.util.List;

import lombok.Data;

@Data
public class IntentNodeTreeRespDTO {

    private Long id;

    private String nodeId;

    private String nodeName;

    private String parentId;

    private String nodeType;

    private String description;

    private String promptTemplate;

    private String promptSnippet;

    private String paramPromptTemplate;

    private List<String> keywords;

    private Long knowledgeBaseId;

    private String actionService;

    private String mcpToolId;

    private Integer topK;

    private Integer enabled;

    private List<IntentNodeTreeRespDTO> children;
}
