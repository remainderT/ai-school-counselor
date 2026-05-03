DROP DATABASE IF EXISTS ai_school_conselor;
CREATE DATABASE ai_school_conselor  DEFAULT CHARACTER SET utf8mb4;
use ai_school_conselor;

DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
                        `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
                        `username`      varchar(256) NOT NULL COMMENT '用户名',
                        `password`      varchar(512) NOT NULL COMMENT '密码',
                        `mail`          varchar(30)  NOT NULL COMMENT '邮箱',
                        `salt`          varchar(20)  NOT NULL COMMENT '盐',
                        `is_admin`      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否管理员 1:是 0:否',
                        `avatar`        varchar(60)     DEFAULT NULL COMMENT '头像',
                        `create_time` datetime     DEFAULT NULL COMMENT '创建时间',
                        `update_time` datetime     DEFAULT NULL COMMENT '修改时间',
                        `del_flag`    tinyint(1)   DEFAULT 0 COMMENT '删除标识 0：未删除 1：已删除',
                        PRIMARY KEY (`id`),
                        UNIQUE KEY uk_mail (mail),
                        UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

DROP TABLE IF EXISTS knowledge;
CREATE TABLE knowledge (
                        id           BIGINT(20)       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        user_id      BIGINT(20)       NOT NULL COMMENT '创建用户ID',
                        name         VARCHAR(128)     NOT NULL COMMENT '知识库名称（同时作为 RustFS Bucket 和 Milvus Collection 名称，下划线在使用时自动转连字符）',
                        description  VARCHAR(512)     DEFAULT NULL COMMENT '知识库描述',
                        `create_time` datetime       DEFAULT NULL COMMENT '创建时间',
                        `update_time` datetime       DEFAULT NULL COMMENT '修改时间',
                        `del_flag`    tinyint(1)     DEFAULT 0 COMMENT '删除标识 0：未删除 1：已删除',
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_user_name (user_id, name) COMMENT '同一用户下知识库名唯一'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库表';

DROP TABLE IF EXISTS document;
CREATE TABLE document (
                        id                  BIGINT(20)       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        md5_hash            VARCHAR(32)      NOT NULL COMMENT '文档MD5哈希值',
                        original_file_name  VARCHAR(255)     NOT NULL COMMENT '原始文件名',
                        file_size_bytes     BIGINT           NOT NULL COMMENT '文件大小（字节）',
                        processing_status   TINYINT          NOT NULL DEFAULT 0 COMMENT '处理状态：0-待处理，1-处理中，2-已完成，-1-失败',
                        user_id             BIGINT(20)       NOT NULL COMMENT '上传用户标识',
                        knowledge_id        BIGINT(20)       NOT NULL COMMENT '绑定知识库ID',
                        source_url          VARCHAR(1024)    DEFAULT NULL COMMENT '来源URL（URL上传时记录）',
                        schedule_enabled    TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '是否启用定时刷新：1-启用，0-禁用',
                        schedule_cron       VARCHAR(128)     DEFAULT NULL COMMENT '定时刷新cron表达式',
                        chunk_mode          VARCHAR(32)      DEFAULT NULL COMMENT '离线分块模式',
                        next_refresh_at     DATETIME         DEFAULT NULL COMMENT '下次定时刷新时间',
                        last_refresh_at     DATETIME         DEFAULT NULL COMMENT '上次定时刷新时间',
                        failure_reason      VARCHAR(512)     DEFAULT NULL COMMENT '失败原因',
                        processed_at        DATETIME         DEFAULT NULL COMMENT '处理完成时间',
                        `create_time` datetime     DEFAULT NULL COMMENT '创建时间',
                        `update_time` datetime     DEFAULT NULL COMMENT '修改时间',
                        `del_flag`    tinyint(1)   DEFAULT 0 COMMENT '删除标识 0：未删除 1：已删除',
                        PRIMARY KEY (id),
                        INDEX idx_user_md5_hash (user_id, md5_hash) COMMENT '用户+MD5索引，加速重复检测查询',
                        INDEX idx_knowledge_id (knowledge_id) COMMENT '知识库索引',
                        INDEX idx_schedule_scan (schedule_enabled, next_refresh_at) COMMENT '定时任务扫描索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档记录表';

DROP TABLE IF EXISTS chunk;
CREATE TABLE chunk (
                        id      BIGINT(20)          NOT NULL AUTO_INCREMENT COMMENT 'chunk唯一标识',
                        document_id     BIGINT(20)       NOT NULL COMMENT '关联文档ID',
                        fragment_index  INT              NOT NULL COMMENT 'chunk序号',
                        text_data       TEXT             COMMENT 'chunk文本内容',
                        encoding_model  VARCHAR(32)      COMMENT 'chunk编码模型版本',
                        md5_hash         VARCHAR(32)     COMMENT 'chunk文本MD5哈希值',
                        token_estimate   INT             COMMENT 'chunk token 估算值',
                        `del_flag`       tinyint(1)      DEFAULT 0 COMMENT '删除标识 0：未删除 1：已删除',
                        PRIMARY KEY (id),
                        INDEX idx_document_id (document_id) COMMENT '文档ID索引',
                        INDEX idx_chunk (document_id, fragment_index) COMMENT '文档和chunk序号组合索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档chunk存储表';

DROP TABLE IF EXISTS intent_node;
CREATE TABLE intent_node (
                        id                BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        node_id           VARCHAR(128) NOT NULL COMMENT '节点业务ID',
                        node_name         VARCHAR(128) NOT NULL COMMENT '节点名称',
                        parent_id         VARCHAR(128) DEFAULT NULL COMMENT '父节点业务ID',
                        node_type         VARCHAR(32)  NOT NULL COMMENT '节点类型',
                        description       VARCHAR(512) DEFAULT NULL COMMENT '节点描述',
                        prompt_template   TEXT         DEFAULT NULL COMMENT '场景模板',
                        prompt_snippet    VARCHAR(512) DEFAULT NULL COMMENT '短规则片段',
                        param_prompt_template TEXT      DEFAULT NULL COMMENT '参数提取模板',
                        keywords_json     TEXT         DEFAULT NULL COMMENT '关键词JSON数组',
                        examples_json     TEXT         DEFAULT NULL COMMENT '示例问题JSON数组',
                        knowledge_base_id BIGINT(20)   DEFAULT NULL COMMENT '关联知识库ID',
                        action_service    VARCHAR(64)  DEFAULT NULL COMMENT '工具服务名',
                        mcp_tool_id       VARCHAR(128) DEFAULT NULL COMMENT 'MCP工具ID',
                        top_k             INT          DEFAULT NULL COMMENT '节点级检索TopK',
                        enabled           TINYINT(1)   DEFAULT 1 COMMENT '是否启用 1:启用 0:停用',
                        create_time       DATETIME     DEFAULT NULL COMMENT '创建时间',
                        update_time       DATETIME     DEFAULT NULL COMMENT '修改时间',
                        del_flag          TINYINT(1)   DEFAULT 0 COMMENT '删除标识 0：未删除 1：已删除',
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_node_id (node_id),
                        INDEX idx_parent_id (parent_id),
                        INDEX idx_node_enabled (enabled, del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='意图树节点表';


DROP TABLE IF EXISTS conversation;
CREATE TABLE conversation (
                        id          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        session_id  VARCHAR(64)  NOT NULL COMMENT '会话唯一标识',
                        user_id     BIGINT(20)   NOT NULL COMMENT '用户ID',
                        title       VARCHAR(128) NOT NULL DEFAULT '新会话' COMMENT '会话标题',
                        last_time   DATETIME     DEFAULT NULL COMMENT '最近消息时间',
                        create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                        update_time DATETIME     DEFAULT NULL COMMENT '修改时间',
                        del_flag    TINYINT(1)   DEFAULT 0 COMMENT '删除标识 0：未删除 1：已删除',
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_session_id (session_id),
                        INDEX idx_user_time (user_id, last_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

DROP TABLE IF EXISTS messages;
CREATE TABLE messages (
                        id          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        session_id  VARCHAR(64)  NOT NULL COMMENT '会话标识',
                        user_id     BIGINT(20)   NOT NULL COMMENT '用户标识',
                        role        VARCHAR(16)  NOT NULL COMMENT '角色：user/assistant',
                        content     LONGTEXT     NOT NULL COMMENT '消息内容',
                        score       TINYINT      DEFAULT NULL COMMENT '用户评分（1-5），仅 assistant 消息有值',
                        created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        PRIMARY KEY (id),
                        INDEX idx_session (session_id) COMMENT '会话索引',
                        INDEX idx_user (user_id) COMMENT '用户索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息表';

DROP TABLE IF EXISTS message_sources;
CREATE TABLE message_sources (
                        id               BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        message_id       BIGINT(20)   NOT NULL COMMENT '消息ID',
                        document_id      BIGINT(20)   NULL COMMENT '关联文档ID',
                        document_md5     VARCHAR(32)  NOT NULL COMMENT '文档MD5（冗余，便于快速写入）',
                        chunk_id         INT          NULL COMMENT '片段序号',
                        relevance_score  DOUBLE       NULL COMMENT '相关度分数',
                        source_file_name VARCHAR(255) NULL COMMENT '来源文件名',
                        created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        PRIMARY KEY (id),
                        INDEX idx_message (message_id) COMMENT '消息索引',
                        INDEX idx_document_id (document_id) COMMENT '文档ID索引',
                        INDEX idx_doc_md5 (document_md5) COMMENT '文档MD5索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息来源表';



DROP TABLE IF EXISTS message_summary;
CREATE TABLE message_summary (
                        id              BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        session_id      VARCHAR(64)  NOT NULL COMMENT '会话ID',
                        user_id         BIGINT(20)   NULL COMMENT '用户ID',
                        content         TEXT         NOT NULL COMMENT '摘要内容',
                        last_message_id BIGINT(20)   NULL COMMENT '摘要覆盖到的最后消息ID',
                        created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                        PRIMARY KEY (id),
                        INDEX idx_summary_session (session_id),
                        INDEX idx_summary_session_msg (session_id, last_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话摘要表';

DROP TABLE IF EXISTS rag_trace_node;
DROP TABLE IF EXISTS rag_trace_run;

CREATE TABLE rag_trace_run (
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

CREATE TABLE rag_trace_node (
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

INSERT INTO `user` (`id`, `username`, `password`, `mail`, `salt`, `is_admin`, `avatar`, `create_time`, `update_time`, `del_flag`)
VALUES (1, 'admin', 'b9d11b3be25f5a1a7dc8ca04cd310b28', 'admin@example.com', 'admin', 1, NULL, NOW(), NOW(), 0);