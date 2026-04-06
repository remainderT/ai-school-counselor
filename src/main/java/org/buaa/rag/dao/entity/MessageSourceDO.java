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
 * 对话消息来源记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("message_sources")
public class MessageSourceDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long messageId;

    private Long documentId;

    private String documentMd5;

    private Integer chunkId;

    private Double relevanceScore;

    private String sourceFileName;

    private LocalDateTime createdAt;
}
