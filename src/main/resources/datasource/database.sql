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
                        id           BIGINT           NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        user_id      BIGINT(20)       NOT NULL COMMENT '创建用户ID',
                        name         VARCHAR(128)     NOT NULL COMMENT '知识库名称',
                        description  VARCHAR(512)     DEFAULT NULL COMMENT '知识库描述',
                        `create_time` datetime       DEFAULT NULL COMMENT '创建时间',
                        `update_time` datetime       DEFAULT NULL COMMENT '修改时间',
                        `del_flag`    tinyint(1)     DEFAULT 0 COMMENT '删除标识 0：未删除 1：已删除',
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_user_name (user_id, name) COMMENT '同一用户下知识库名唯一'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库表';

DROP TABLE IF EXISTS document;
CREATE TABLE document (
                        id                  BIGINT           NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        md5_hash            VARCHAR(32)      NOT NULL COMMENT '文档MD5哈希值',
                        original_file_name  VARCHAR(255)     NOT NULL COMMENT '原始文件名',
                        file_size_bytes     BIGINT           NOT NULL COMMENT '文件大小（字节）',
                        processing_status   TINYINT          NOT NULL DEFAULT 0 COMMENT '处理状态：0-待处理，1-处理中，2-已完成，-1-失败',
                        user_id             bigint(20)      NOT NULL COMMENT '上传用户标识',
                        knowledge_id        BIGINT           NOT NULL COMMENT '绑定知识库ID',
                        failure_reason      VARCHAR(512)     DEFAULT NULL COMMENT '失败原因',
                        processed_at        DATETIME         DEFAULT NULL COMMENT '处理完成时间',
                        `create_time` datetime     DEFAULT NULL COMMENT '创建时间',
                        `update_time` datetime     DEFAULT NULL COMMENT '修改时间',
                        `del_flag`    tinyint(1)   DEFAULT 0 COMMENT '删除标识 0：未删除 1：已删除',
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_user_md5_hash (user_id, md5_hash) COMMENT '用户内MD5唯一索引，防止重复上传',
                        INDEX idx_knowledge_id (knowledge_id) COMMENT '知识库索引'
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
                        id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        node_id           VARCHAR(128) NOT NULL COMMENT '节点业务ID',
                        node_name         VARCHAR(128) NOT NULL COMMENT '节点名称',
                        parent_id         VARCHAR(128) DEFAULT NULL COMMENT '父节点业务ID',
                        node_type         VARCHAR(32)  NOT NULL COMMENT '节点类型',
                        description       VARCHAR(512) DEFAULT NULL COMMENT '节点描述',
                        prompt_template   TEXT         DEFAULT NULL COMMENT '场景模板',
                        prompt_snippet    VARCHAR(512) DEFAULT NULL COMMENT '短规则片段',
                        param_prompt_template TEXT      DEFAULT NULL COMMENT '参数提取模板',
                        keywords_json     TEXT         DEFAULT NULL COMMENT '关键词JSON数组',
                        knowledge_base_id VARCHAR(64)  DEFAULT NULL COMMENT '关联知识库ID',
                        action_service    VARCHAR(64)  DEFAULT NULL COMMENT '工具服务名',
                        node_level        VARCHAR(32)  DEFAULT NULL COMMENT '节点层级',
                        node_kind         VARCHAR(32)  DEFAULT NULL COMMENT '节点语义类型',
                        mcp_tool_id       VARCHAR(128) DEFAULT NULL COMMENT 'MCP工具ID',
                        top_k             INT          DEFAULT NULL COMMENT '节点级检索TopK',
                        sort_order        INT          DEFAULT 0 COMMENT '排序',
                        enabled           TINYINT(1)   DEFAULT 1 COMMENT '是否启用 1:启用 0:停用',
                        create_time       DATETIME     DEFAULT NULL COMMENT '创建时间',
                        update_time       DATETIME     DEFAULT NULL COMMENT '修改时间',
                        del_flag          TINYINT(1)   DEFAULT 0 COMMENT '删除标识 0：未删除 1：已删除',
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_node_id (node_id),
                        INDEX idx_parent_id (parent_id),
                        INDEX idx_node_enabled (enabled, del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='意图树节点表';

DROP TABLE IF EXISTS messages;
CREATE TABLE messages (
                        id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        session_id  VARCHAR(64)  NOT NULL COMMENT '会话标识',
                        user_id     bigint(20)  NOT NULL COMMENT '用户标识',
                        role        VARCHAR(16)  NOT NULL COMMENT '角色：user/assistant',
                        content     LONGTEXT     NOT NULL COMMENT '消息内容',
                        created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        PRIMARY KEY (id),
                        INDEX idx_session (session_id) COMMENT '会话索引',
                        INDEX idx_user (user_id) COMMENT '用户索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息表';

DROP TABLE IF EXISTS message_sources;
CREATE TABLE message_sources (
                        id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        message_id       BIGINT       NOT NULL COMMENT '消息ID',
                        document_md5     VARCHAR(32)  NOT NULL COMMENT '文档MD5',
                        chunk_id         INT          NULL COMMENT '片段序号',
                        relevance_score  DOUBLE       NULL COMMENT '相关度分数',
                        source_file_name VARCHAR(255) NULL COMMENT '来源文件名',
                        created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        PRIMARY KEY (id),
                        INDEX idx_message (message_id) COMMENT '消息索引',
                        INDEX idx_doc (document_md5) COMMENT '文档索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息来源表';

DROP TABLE IF EXISTS message_feedback;
CREATE TABLE message_feedback (
                        id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        message_id  BIGINT       NOT NULL COMMENT '消息ID',
                        user_id     VARCHAR(64)  NOT NULL COMMENT '用户标识',
                        score       TINYINT      NOT NULL COMMENT '评分（1-5）',
                        comment     VARCHAR(255) NULL COMMENT '反馈备注',
                        created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        PRIMARY KEY (id),
                        INDEX idx_feedback_message (message_id) COMMENT '消息索引',
                        INDEX idx_feedback_user (user_id) COMMENT '用户索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息反馈表';

DROP TABLE IF EXISTS message_summary;
CREATE TABLE message_summary (
                        id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                        session_id      VARCHAR(64)  NOT NULL COMMENT '会话ID',
                        user_id         VARCHAR(64)  NULL COMMENT '用户ID',
                        content         TEXT         NOT NULL COMMENT '摘要内容',
                        last_message_id BIGINT       NULL COMMENT '摘要覆盖到的最后消息ID',
                        created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                        PRIMARY KEY (id),
                        INDEX idx_summary_session (session_id),
                        INDEX idx_summary_session_msg (session_id, last_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话摘要表';

INSERT INTO `user` (`id`, `username`, `password`, `mail`, `salt`, `avatar`, `create_time`, `update_time`, `del_flag`)
VALUES (1, 'admin', 'f5866c4a4d6014ecced47960c2e3d07f', 'admin@example.com', 'admin', NULL, NOW(), NOW(), 0);

INSERT INTO `knowledge` (`id`, `user_id`, `name`, `description`,  `create_time`, `update_time`, `del_flag`)
VALUES (1, 1, 'test', '测试知识库', NOW(), NOW(), 0);
