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
 * RAG 链路节点记录（对应 t_rag_trace_node 表）
 * 每个 @RagTraceNode 标注的方法调用对应一条 Node 记录，通过 parentNodeId 维护树形结构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_rag_trace_node")
public class RagTraceNodeDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属链路 ID */
    private String traceId;

    /** 节点唯一 ID（雪花 ID 字符串） */
    private String nodeId;

    /** 父节点 ID（null 表示根的直接子节点） */
    private String parentNodeId;

    /** 节点深度（0 = 根直接子节点） */
    private Integer depth;

    /**
     * 节点类型：
     * REWRITE / INTENT / RETRIEVE / RETRIEVE_CHANNEL / LLM_CHAT / METHOD
     */
    private String nodeType;

    /** 节点名称（来自 @RagTraceNode.name） */
    private String nodeName;

    /** 完整类名 */
    private String className;

    /** 方法名 */
    private String methodName;

    /** 状态：RUNNING / SUCCESS / ERROR */
    private String status;

    /** 错误信息 */
    private String errorMessage;

    /** 开始时间（毫秒精度） */
    private Date startTime;

    /** 结束时间 */
    private Date endTime;

    /** 耗时（毫秒） */
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
