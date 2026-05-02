package org.buaa.rag.dto.resp;

import lombok.Data;

/**
 * RAG 链路节点 VO（前端瀑布图展示）
 */
@Data
public class RagTraceNodeVO {
    private String  traceId;
    private String  nodeId;
    private String  parentNodeId;
    private Integer depth;
    private String  nodeType;
    private String  nodeName;
    private String  className;
    private String  methodName;
    private String  status;
    private String  errorMessage;
    private Long    durationMs;
    private String  startTime;
    private String  endTime;
}
