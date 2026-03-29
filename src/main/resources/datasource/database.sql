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
                        examples_json     TEXT         DEFAULT NULL COMMENT '示例问题JSON数组',
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

INSERT INTO `knowledge` (`id`, `user_id`, `name`, `description`,  `create_time`, `update_time`, `del_flag`) VALUES
(1, 1, 'test', '测试知识库（默认）', NOW(), NOW(), 0),
(2, 1, 'academic_kb', '教务教学知识库', NOW(), NOW(), 0),
(3, 1, 'affairs_kb', '学生事务与奖助知识库', NOW(), NOW(), 0),
(4, 1, 'campus_life_kb', '校园生活服务知识库', NOW(), NOW(), 0),
(5, 1, 'career_kb', '就业与职业发展知识库', NOW(), NOW(), 0),
(6, 1, 'psy_safety_kb', '心理与安全知识库', NOW(), NOW(), 0);

INSERT INTO intent_node (node_id, node_name, parent_id, node_type, description, prompt_template, prompt_snippet, param_prompt_template, keywords_json, examples_json, knowledge_base_id, action_service, node_level, node_kind, mcp_tool_id, top_k, sort_order, enabled, create_time, update_time, del_flag) VALUES
  ('root', '根节点', NULL, 'GROUP', '高校辅导员全域问答和办事', NULL, NULL, NULL, '[]', NULL, NULL, NULL, 'DOMAIN', 'SYSTEM', NULL, NULL, 0, 1, NOW(), NOW(), 0),
  ('academic', '教务教学', 'root', 'GROUP', '学籍 选课 考试 成绩 保研 推免', NULL, NULL, NULL, '["学籍", "课程", "选课", "考试", "成绩", "保研", "推免"]', NULL, NULL, NULL, 'CATEGORY', 'SYSTEM', NULL, NULL, 1, 1, NOW(), NOW(), 0),
  ('academic_status', '学籍管理', 'academic', 'RAG_QA', '休学 复学 学籍注册 注销 转专业 专业分流', NULL, NULL, NULL, '["休学", "复学", "注册", "注销", "转专业", "分流"]', NULL, '2', NULL, 'TOPIC', 'KB', NULL, NULL, 2, 1, NOW(), NOW(), 0),
  ('academic_course', '课程与选课', 'academic', 'RAG_QA', '选课规则 重修 补考 学分认定', NULL, NULL, NULL, '["选课", "重修", "补考", "学分"]', NULL, '2', NULL, 'TOPIC', 'KB', NULL, NULL, 3, 1, NOW(), NOW(), 0),
  ('academic_exam', '考试与成绩', 'academic', 'GROUP', '考试安排 成绩绩点 违纪处理', NULL, NULL, NULL, '["考试", "绩点", "成绩", "GPA", "四六级"]', NULL, NULL, NULL, 'TOPIC', 'SYSTEM', NULL, NULL, 4, 1, NOW(), NOW(), 0),
  ('academic_exam_policy', '考试政策', 'academic_exam', 'RAG_QA', '考试安排 四六级报名 考场纪律', NULL, NULL, NULL, '["考试安排", "四六级", "纪律"]', '["期末考试时间", "补考什么时候", "考试地点在哪"]', '2', NULL, 'TOPIC', 'KB', NULL, NULL, 5, 1, NOW(), NOW(), 0),
  ('academic_score_api', '成绩查询', 'academic_exam', 'API_ACTION', '查询成绩与绩点', NULL, NULL, NULL, '["查成绩", "成绩单", "绩点", "GPA"]', '["怎么查成绩", "绩点在哪里看", "成绩查询入口是什么"]', NULL, 'score', 'TOPIC', 'MCP', NULL, NULL, 6, 1, NOW(), NOW(), 0),
  ('student_affairs', '学生事务与奖助', 'root', 'GROUP', '奖学金 资助 请假 综测 行政办理', NULL, NULL, NULL, '["奖学金", "资助", "助学贷款", "请假", "综测", "证明"]', NULL, NULL, NULL, 'CATEGORY', 'SYSTEM', NULL, NULL, 7, 1, NOW(), NOW(), 0),
  ('affairs_scholarship', '奖学金与荣誉', 'student_affairs', 'GROUP', '国家奖学金 校级奖学金 社会奖学金 评优评先', NULL, NULL, NULL, '["奖学金", "国奖", "校奖", "社会奖学金", "三好学生", "优秀干部"]', NULL, NULL, NULL, 'TOPIC', 'SYSTEM', NULL, NULL, 8, 1, NOW(), NOW(), 0),
  ('scholarship_national', '国家奖学金', 'affairs_scholarship', 'RAG_QA', '国家奖学金申请条件 流程 材料 时间', NULL, NULL, NULL, '["国家奖学金", "国奖", "励志奖学金"]', '["国家奖学金怎么申请", "国奖申请条件", "国家奖学金评审流程"]', '3', NULL, 'TOPIC', 'KB', NULL, NULL, 9, 1, NOW(), NOW(), 0),
  ('scholarship_campus', '校级奖学金', 'affairs_scholarship', 'RAG_QA', '校级奖学金评定细则 评审流程', NULL, NULL, NULL, '["校级奖学金", "校奖", "院奖"]', '["校级奖学金有哪些", "校奖怎么申请", "校级奖学金评选条件"]', '3', NULL, 'TOPIC', 'KB', NULL, NULL, 10, 1, NOW(), NOW(), 0),
  ('scholarship_social', '社会奖学金', 'affairs_scholarship', 'RAG_QA', '社会捐赠奖学金申请与评审', NULL, NULL, NULL, '["社会奖学金", "捐赠奖学金"]', NULL, '3', NULL, 'TOPIC', 'KB', NULL, NULL, 11, 1, NOW(), NOW(), 0),
  ('affairs_funding', '资助与贷款', 'student_affairs', 'RAG_QA', '助学贷款 困难认定 勤工助学', NULL, NULL, NULL, '["助学贷款", "困难生", "勤工助学"]', NULL, '3', NULL, 'TOPIC', 'KB', NULL, NULL, 12, 1, NOW(), NOW(), 0),
  ('affairs_admin', '日常行政', 'student_affairs', 'GROUP', '请假销假 综测 在读证明 成绩单打印', NULL, NULL, NULL, '["请假", "销假", "综测", "在读证明", "成绩单"]', NULL, NULL, NULL, 'TOPIC', 'SYSTEM', NULL, NULL, 13, 1, NOW(), NOW(), 0),
  ('affairs_leave_api', '请假销假办理', 'affairs_admin', 'API_ACTION', '创建请假申请草稿和办理指引', NULL, NULL, NULL, '["请假", "销假", "病假", "事假"]', '["怎么请假", "请假流程", "病假需要什么材料"]', NULL, 'leave', 'TOPIC', 'MCP', NULL, NULL, 14, 1, NOW(), NOW(), 0),
  ('affairs_admin_policy', '行政流程咨询', 'affairs_admin', 'RAG_QA', '综测证明办理流程', NULL, NULL, NULL, '["综测", "证明", "打印"]', NULL, '3', NULL, 'TOPIC', 'KB', NULL, NULL, 15, 1, NOW(), NOW(), 0),
  ('campus_life', '校园生活服务', 'root', 'GROUP', '宿舍 校园卡 网络 后勤 校医院', NULL, NULL, NULL, '["宿舍", "校园卡", "网络", "后勤", "医保", "校车"]', NULL, NULL, NULL, 'CATEGORY', 'SYSTEM', NULL, NULL, 16, 1, NOW(), NOW(), 0),
  ('life_dorm', '宿舍管理', 'campus_life', 'GROUP', '门禁 归寝 报修 调宿 水电费', NULL, NULL, NULL, '["宿舍", "门禁", "归寝", "报修", "调宿", "水电"]', NULL, NULL, NULL, 'TOPIC', 'SYSTEM', NULL, NULL, 17, 1, NOW(), NOW(), 0),
  ('life_repair_api', '后勤报修', 'life_dorm', 'API_ACTION', '创建报修工单草稿', NULL, NULL, NULL, '["报修", "维修", "坏了", "漏水", "停电"]', '["宿舍门坏了报修", "水龙头漏水找谁", "寝室灯坏了"]', NULL, 'repair', 'TOPIC', 'MCP', NULL, NULL, 18, 1, NOW(), NOW(), 0),
  ('life_dorm_policy', '宿舍制度', 'life_dorm', 'RAG_QA', '门禁归寝和调宿政策', NULL, NULL, NULL, '["门禁", "归寝", "调宿"]', NULL, '4', NULL, 'TOPIC', 'KB', NULL, NULL, 19, 1, NOW(), NOW(), 0),
  ('life_card_net', '校园卡与网络', 'campus_life', 'RAG_QA', '校园网开通 一卡通充值 挂失', NULL, NULL, NULL, '["校园网", "一卡通", "充值", "挂失"]', NULL, '4', NULL, 'TOPIC', 'KB', NULL, NULL, 20, 1, NOW(), NOW(), 0),
  ('life_medical', '医疗与后勤', 'campus_life', 'RAG_QA', '校医院医保报销 校车时刻表', NULL, NULL, NULL, '["校医院", "医保", "报销", "校车"]', NULL, '4', NULL, 'TOPIC', 'KB', NULL, NULL, 21, 1, NOW(), NOW(), 0),
  ('life_weather_mcp', '天气查询', 'campus_life', 'API_ACTION', '查询城市当前天气与未来天气预报', NULL, NULL, NULL, '["天气", "气温", "温度", "下雨", "降雨", "预报"]', '["北京今天天气怎么样", "上海明天会下雨吗", "杭州未来三天天气"]', NULL, 'weather', 'TOPIC', 'MCP', NULL, NULL, 22, 1, NOW(), NOW(), 0),
  ('career', '就业与职业发展', 'root', 'GROUP', '就业手续 档案 报到证 保研 考研 调剂', NULL, NULL, NULL, '["就业", "三方", "档案", "报到证", "考研", "保研", "调剂"]', NULL, NULL, NULL, 'CATEGORY', 'SYSTEM', NULL, NULL, 23, 1, NOW(), NOW(), 0),
  ('career_employ', '就业手续', 'career', 'RAG_QA', '三方协议 档案转递 户口迁移 报到证办理', NULL, NULL, NULL, '["三方", "档案", "户口", "报到证"]', NULL, '5', NULL, 'TOPIC', 'KB', NULL, NULL, 24, 1, NOW(), NOW(), 0),
  ('career_admission', '升学指导', 'career', 'RAG_QA', '保研推免 考研复习 调剂指导', NULL, NULL, NULL, '["保研", "推免", "考研", "调剂"]', '["保研要求是什么", "如何申请推免", "计算机学院保研规则"]', '5', NULL, 'TOPIC', 'KB', NULL, NULL, 25, 1, NOW(), NOW(), 0),
  ('psy', '心理与安全', 'root', 'GROUP', '心理咨询 情绪调节 防诈骗 紧急求助', NULL, NULL, NULL, '["心理", "情绪", "热线", "安全", "诈骗", "求助"]', '["我压力很大怎么办", "情绪低落怎么办", "情绪崩溃怎么办"]', NULL, NULL, 'CATEGORY', 'SYSTEM', NULL, NULL, 26, 1, NOW(), NOW(), 0),
  ('psy_counseling', '心理咨询', 'psy', 'RAG_QA', '心理中心预约流程 紧急心理援助热线', NULL, NULL, NULL, '["心理咨询", "心理中心", "援助热线"]', NULL, '6', NULL, 'TOPIC', 'KB', NULL, NULL, 27, 1, NOW(), NOW(), 0),
  ('psy_safety', '校园安全', 'psy', 'RAG_QA', '防诈骗指南 紧急联系人', NULL, NULL, NULL, '["诈骗", "安全", "求助", "报警"]', NULL, '6', NULL, 'TOPIC', 'KB', NULL, NULL, 28, 1, NOW(), NOW(), 0),
  ('chitchat', '日常闲聊', 'root', 'CHITCHAT', '打招呼与日常聊天', '这是闲聊场景，请保持简洁友好回答。', NULL, NULL, '["你好", "在吗", "谢谢", "聊聊"]', '["你好", "在吗", "聊聊天"]', NULL, NULL, 'CATEGORY', 'SYSTEM', NULL, NULL, 29, 1, NOW(), NOW(), 0);
