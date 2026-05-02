package org.buaa.rag.dao.entity;

import java.util.Date;

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
 * RAG 链路运行记录（对应 t_rag_trace_run 表）
 * 每次完整的 RAG 请求对应一条 Run 记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_rag_trace_run")
public class RagTraceRunDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全局链路 ID（雪花 ID 字符串，业务唯一键） */
    private String traceId;

    /** 链路名称（来自 @RagTraceRoot.name） */
    private String traceName;

    /** 入口方法（类名#方法名） */
    private String entryMethod;

    /** 关联会话 ID */
    private String conversationId;

    /** 关联任务 ID（SSE taskId） */
    private String taskId;

    /** 用户 ID */
    private String userId;

    /** 状态：RUNNING / SUCCESS / ERROR */
    private String status;

    /** 错误信息（截断至 1000 字符） */
    private String errorMessage;

    /** 开始时间（毫秒精度） */
    private Date startTime;

    /** 结束时间 */
    private Date endTime;

    /** 总耗时（毫秒） */
    private Long durationMs;

    /** 扩展字段（JSON） */
    private String extraData;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
