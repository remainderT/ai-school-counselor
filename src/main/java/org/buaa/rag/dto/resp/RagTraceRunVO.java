package org.buaa.rag.dto.resp;

import lombok.Data;

/**
 * RAG 链路运行记录 VO（前端列表展示）
 */
@Data
public class RagTraceRunVO {
    private String traceId;
    private String traceName;
    private String conversationId;
    private String taskId;
    private String userId;
    private String status;
    private String errorMessage;
    private Long   durationMs;
    private String startTime;
    private String endTime;
}
