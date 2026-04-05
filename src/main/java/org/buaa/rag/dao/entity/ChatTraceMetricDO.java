package org.buaa.rag.dao.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 在线链路轻量指标
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_trace_metrics")
public class ChatTraceMetricDO {

    private Long id;

    private String sessionId;

    private Long messageId;

    private String userId;

    private String queryText;

    private Long rewriteLatencyMs;

    private Double retrievalHitRate;

    private Double citationRate;

    private Integer clarifyTriggered;

    private Integer userFeedbackScore;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
