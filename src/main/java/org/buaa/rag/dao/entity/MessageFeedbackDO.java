package org.buaa.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话消息反馈
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("message_feedback")
public class MessageFeedbackDO {

    private Long id;

    private Long messageId;

    private Long userId;

    private Integer score;

    private String comment;

    private LocalDateTime createdAt;
}
