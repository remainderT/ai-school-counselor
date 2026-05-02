-- RAG 全链路 Trace 表（追加到现有数据库）
-- 执行：在 ai_school_conselor 数据库中执行以下 SQL

USE ai_school_conselor;

-- ─── 链路主记录表 ───────────────────────────────────────────────
DROP TABLE IF EXISTS t_rag_trace_node;
DROP TABLE IF EXISTS t_rag_trace_run;

CREATE TABLE t_rag_trace_run (
    id              BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    trace_id        VARCHAR(64)   NOT NULL COMMENT '全局链路ID（雪花ID）',
    trace_name      VARCHAR(128)  DEFAULT NULL COMMENT '链路名称（来自@RagTraceRoot.name）',
    entry_method    VARCHAR(256)  DEFAULT NULL COMMENT '入口方法（类名#方法名）',
    conversation_id VARCHAR(64)   DEFAULT NULL COMMENT '会话ID',
    task_id         VARCHAR(64)   DEFAULT NULL COMMENT '任务ID（SSE taskId）',
    user_id         VARCHAR(64)   DEFAULT NULL COMMENT '用户ID',
    status          VARCHAR(16)   NOT NULL DEFAULT 'RUNNING' COMMENT '状态：RUNNING/SUCCESS/ERROR',
    error_message   VARCHAR(1000) DEFAULT NULL COMMENT '错误信息（截断至1000字符）',
    start_time      DATETIME(3)   DEFAULT NULL COMMENT '开始时间（毫秒精度）',
    end_time        DATETIME(3)   DEFAULT NULL COMMENT '结束时间',
    duration_ms     BIGINT(20)    DEFAULT NULL COMMENT '总耗时（毫秒）',
    extra_data      TEXT          DEFAULT NULL COMMENT '扩展字段（JSON）',
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    update_time     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT(1)    DEFAULT 0 COMMENT '逻辑删除标识',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trace_id (trace_id),
    KEY idx_task_id (task_id),
    KEY idx_user_id (user_id),
    KEY idx_conversation_id (conversation_id),
    KEY idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 链路运行记录表';

-- ─── 链路节点记录表 ──────────────────────────────────────────────
CREATE TABLE t_rag_trace_node (
    id              BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    trace_id        VARCHAR(64)   NOT NULL COMMENT '所属链路ID',
    node_id         VARCHAR(64)   NOT NULL COMMENT '节点唯一ID',
    parent_node_id  VARCHAR(64)   DEFAULT NULL COMMENT '父节点ID（null表示根直接子节点）',
    depth           INT(11)       DEFAULT 0 COMMENT '节点深度（0=根直接子节点）',
    node_type       VARCHAR(64)   DEFAULT NULL COMMENT '节点类型（REWRITE/INTENT/RETRIEVE/LLM_ROUTING等）',
    node_name       VARCHAR(128)  DEFAULT NULL COMMENT '节点名称（来自@RagTraceNode.name）',
    class_name      VARCHAR(256)  DEFAULT NULL COMMENT '完整类名',
    method_name     VARCHAR(128)  DEFAULT NULL COMMENT '方法名',
    status          VARCHAR(16)   NOT NULL DEFAULT 'RUNNING' COMMENT '状态：RUNNING/SUCCESS/ERROR',
    error_message   VARCHAR(1000) DEFAULT NULL COMMENT '错误信息',
    start_time      DATETIME(3)   DEFAULT NULL COMMENT '开始时间（毫秒精度）',
    end_time        DATETIME(3)   DEFAULT NULL COMMENT '结束时间',
    duration_ms     BIGINT(20)    DEFAULT NULL COMMENT '耗时（毫秒）',
    extra_data      TEXT          DEFAULT NULL COMMENT '扩展字段（JSON）',
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT(1)    DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trace_node (trace_id, node_id),
    KEY idx_node_trace_id (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 链路节点记录表';
