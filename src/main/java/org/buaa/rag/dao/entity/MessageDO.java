package org.buaa.rag.dao.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话消息记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("messages")
public class MessageDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private Long userId;

    private String role;

    private String content;

    /** 用户评分（1-5），仅 assistant 消息有值，其余为 null */
    private Integer score;

    private LocalDateTime createdAt;
}
