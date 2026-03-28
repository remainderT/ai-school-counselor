package org.buaa.rag.dao.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话摘要记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("message_summary")
public class MessageSummaryDO {

    private Long id;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 用户ID（可选，用于审计）
     */
    private String userId;

    /**
     * 摘要正文
     */
    private String content;

    /**
     * 摘要覆盖到的最后一条消息ID
     */
    private Long lastMessageId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
