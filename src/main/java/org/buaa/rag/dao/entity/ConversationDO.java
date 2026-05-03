package org.buaa.rag.dao.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话记录，对应数据库表 conversation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("conversation")
public class ConversationDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话唯一标识（UUID） */
    private String sessionId;

    /** 归属用户ID */
    private Long userId;

    /** 会话标题 */
    private String title;

    /** 最近一条消息时间 */
    private LocalDateTime lastTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer delFlag;
}
