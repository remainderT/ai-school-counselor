package org.buaa.rag.core.model;

import lombok.Data;

/**
 * 反馈请求
 */
@Data
public class FeedbackRequest {
    private Long messageId;
    private Integer score;
}
