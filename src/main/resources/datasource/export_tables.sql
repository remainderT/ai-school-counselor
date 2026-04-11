-- MySQL dump 10.13  Distrib 8.0.44, for macos15.7 (arm64)
--
-- Host: localhost    Database: ai_school_conselor
-- ------------------------------------------------------
-- Server version	8.0.44

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `intent_node`
--

DROP TABLE IF EXISTS `intent_node`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `intent_node` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `node_id` varchar(128) NOT NULL COMMENT '节点业务ID',
  `node_name` varchar(128) NOT NULL COMMENT '节点名称',
  `parent_id` varchar(128) DEFAULT NULL COMMENT '父节点业务ID',
  `node_type` varchar(32) NOT NULL COMMENT '节点类型',
  `description` varchar(512) DEFAULT NULL COMMENT '节点描述',
  `prompt_template` text COMMENT '场景模板',
  `prompt_snippet` varchar(512) DEFAULT NULL COMMENT '短规则片段',
  `param_prompt_template` text COMMENT '参数提取模板',
  `keywords_json` text COMMENT '关键词JSON数组',
  `examples_json` text COMMENT '示例问题JSON数组',
  `knowledge_base_id` bigint DEFAULT NULL COMMENT '关联知识库ID',
  `action_service` varchar(64) DEFAULT NULL COMMENT '工具服务名',
  `mcp_tool_id` varchar(128) DEFAULT NULL COMMENT 'MCP工具ID',
  `top_k` int DEFAULT NULL COMMENT '节点级检索TopK',
  `enabled` tinyint(1) DEFAULT '1' COMMENT '是否启用 1:启用 0:停用',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `del_flag` tinyint(1) DEFAULT '0' COMMENT '删除标识 0：未删除 1：已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_id` (`node_id`),
  KEY `idx_parent_id` (`parent_id`),
  KEY `idx_node_enabled` (`enabled`,`del_flag`)
) ENGINE=InnoDB AUTO_INCREMENT=148 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='意图树节点表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `intent_node`
--

LOCK TABLES `intent_node` WRITE;
/*!40000 ALTER TABLE `intent_node` DISABLE KEYS */;
INSERT INTO `intent_node` VALUES (1,'root','BUAA AI 辅导员',NULL,'GROUP','高校全域问题咨询与办事服务',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(101,'kb_academic','教务教学','root','GROUP','学籍管理、选课退课、考试安排、成绩查询、培养方案',NULL,NULL,NULL,'[\"教务\", \"选课\", \"考试\", \"成绩\", \"学籍\", \"培养方案\", \"GPA\", \"学分\"]','[\"选课时间\", \"考试安排\", \"成绩查询\", \"培养方案\"]',101,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(102,'kb_affairs','奖助事务','root','GROUP','奖学金评定、助学金申请、勤工助学、综合测评、评优评先',NULL,NULL,NULL,'[\"奖学金\", \"助学金\", \"勤工助学\", \"综测\", \"评优\", \"资助\", \"贷款\"]','[\"奖学金怎么申请\", \"勤工助学岗位\", \"综测评分标准\"]',102,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(103,'kb_finance','财务资产','root','GROUP','差旅报销、劳务发放、采购流程、发票管理、预算编制',NULL,NULL,NULL,'[\"报销\", \"发票\", \"采购\", \"财务\", \"差旅费\", \"劳务费\", \"预算\"]','[\"报销流程\", \"差旅费标准\", \"发票怎么开\"]',103,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(104,'kb_life','校园生活','root','GROUP','宿舍管理、食堂服务、校车时刻、物业报修、校园卡、医疗保健',NULL,NULL,NULL,'[\"宿舍\", \"食堂\", \"校车\", \"报修\", \"校园卡\", \"一卡通\", \"医院\", \"后勤\"]','[\"宿舍报修\", \"校车时刻表\", \"校园卡充值\"]',104,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(105,'kb_career','就业发展','root','GROUP','就业手续、三方协议、档案转递、保研推免、考研指导、就业政策',NULL,NULL,NULL,'[\"就业\", \"三方\", \"档案\", \"保研\", \"考研\", \"招聘\", \"就业手续\"]','[\"三方协议怎么签\", \"档案转递\", \"保研政策\"]',105,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(106,'kb_research','科创科研','root','GROUP','科研项目申报、经费管理、实验室安全、学科竞赛、知识产权、成果转化',NULL,NULL,NULL,'[\"科研\", \"项目\", \"实验室\", \"竞赛\", \"冯如杯\", \"大创\", \"知识产权\"]','[\"科研项目申请\", \"实验室安全\", \"冯如杯报名\"]',106,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(107,'kb_psy','心理安全','root','GROUP','心理咨询、预约流程、情绪调节、防诈骗、消防安全、紧急求助',NULL,NULL,NULL,'[\"心理\", \"咨询\", \"诈骗\", \"消防\", \"安全\", \"求助\", \"情绪\"]','[\"心理咨询预约\", \"遇到诈骗怎么办\", \"消防演习\"]',107,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(108,'kb_integrated','综合规章','root','GROUP','学生手册、规章制度、公文处理、行政事务、通讯录、印信管理',NULL,NULL,NULL,'[\"手册\", \"规章\", \"制度\", \"公文\", \"行政\", \"通讯录\"]','[\"学生手册在哪看\", \"公文格式\", \"行政部门电话\"]',108,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(109,'kb_external','外事交流','root','GROUP','出国交流、签证办理、外事接待、留学生事务、联合培养、国际会议',NULL,NULL,NULL,'[\"出国\", \"签证\", \"交流\", \"外事\", \"留学生\", \"交换\", \"护照\"]','[\"出国交流申请\", \"签证办理流程\", \"留学生事务\"]',109,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(110,'academic_status','学籍管理','kb_academic','RAG_QA','学籍注册、转专业、休学复学、退学、学籍异动',NULL,NULL,NULL,'[\"学籍\",\"注册\",\"转专业\",\"休学\",\"复学\",\"退学\",\"分流\"]','[\"转专业申请\", \"休学流程\", \"学籍注册\"]',101,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(111,'academic_course','选课与培养','kb_academic','RAG_QA','选课规则、培养方案、学分认定、重修补考',NULL,NULL,NULL,'[\"选课\",\"培养方案\",\"学分\",\"重修\",\"补考\",\"退课\"]','[\"选课时间\", \"培养方案查询\", \"学分认定\"]',101,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(112,'academic_exam','考试与成绩','kb_academic','RAG_QA','考试安排、成绩查询、绩点计算、缓考申请',NULL,NULL,NULL,'[\"考试\",\"成绩\",\"GPA\",\"绩点\",\"缓考\",\"四六级\"]','[\"考试安排\", \"成绩查询\", \"GPA计算\"]',101,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(113,'academic_degree','学位与毕业','kb_academic','RAG_QA','学位授予、毕业要求、答辩安排、毕业设计',NULL,NULL,NULL,'[\"学位\",\"毕业\",\"答辩\",\"毕业设计\",\"毕业要求\"]','[\"毕业要求\", \"答辩时间\", \"学位申请\"]',101,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(114,'affairs_scholarship','奖学金评定','kb_affairs','RAG_QA','国家奖学金、学业奖学金、校长奖学金、社会奖学金',NULL,NULL,NULL,'[\"奖学金\",\"国奖\",\"学业奖学金\",\"校长奖\",\"社会奖学金\"]','[\"奖学金申请条件\", \"国家奖学金评选\", \"学业奖学金标准\"]',102,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(115,'affairs_funding','资助与贷款','kb_affairs','RAG_QA','助学贷款、困难认定、勤工助学、临时困难补助',NULL,NULL,NULL,'[\"助学贷款\",\"困难生\",\"勤工助学\",\"补助\",\"资助\"]','[\"助学贷款申请\", \"困难认定标准\", \"勤工助学岗位\"]',102,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(116,'affairs_evaluation','综合测评','kb_affairs','RAG_QA','综合测评评分、德育评定、素质拓展、评优评先',NULL,NULL,NULL,'[\"综测\",\"综合测评\",\"评优\",\"德育\",\"素质拓展\"]','[\"综测评分标准\", \"评优条件\", \"德育分\"]',102,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(117,'finance_reimburse','报销指南','kb_finance','RAG_QA','差旅费报销、会议费报销、日常报销流程',NULL,NULL,NULL,'[\"报销\",\"差旅费\",\"会议费\",\"发票\",\"报销单\"]','[\"差旅费报销\", \"发票要求\", \"报销流程\"]',103,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(118,'finance_payment','劳务与发放','kb_finance','RAG_QA','劳务费发放、评审咨询费、工资发放',NULL,NULL,NULL,'[\"劳务费\",\"工资\",\"发放\",\"评审费\",\"咨询费\"]','[\"劳务费发放标准\", \"工资查询\", \"评审费\"]',103,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(119,'finance_procurement','采购管理','kb_finance','RAG_QA','货物采购、服务采购、采购流程、合同管理',NULL,NULL,NULL,'[\"采购\",\"合同\",\"招标\",\"货物\",\"服务\"]','[\"采购申请\", \"合同签订\", \"采购标准\"]',103,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(120,'life_dorm','宿舍管理','kb_life','RAG_QA','宿舍入住、门禁管理、宿舍报修、调宿申请',NULL,NULL,NULL,'[\"宿舍\",\"门禁\",\"报修\",\"调宿\",\"入住\",\"退宿\"]','[\"宿舍报修\", \"调宿申请\", \"门禁时间\"]',104,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(121,'life_dining','膳食服务','kb_life','RAG_QA','食堂营业时间、菜品推荐、膳食委员会',NULL,NULL,NULL,'[\"食堂\",\"餐厅\",\"菜品\",\"膳食\",\"饭卡\"]','[\"食堂开放时间\", \"菜品推荐\", \"膳食委员会\"]',104,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(122,'life_transport','交通服务','kb_life','RAG_QA','校车时刻表、校园交通、停车管理',NULL,NULL,NULL,'[\"校车\",\"班车\",\"交通\",\"停车\",\"校车时刻\"]','[\"校车时刻表\", \"班车路线\", \"停车收费\"]',104,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(123,'life_card','校园卡务','kb_life','RAG_QA','校园卡办理、充值挂失、一卡通服务',NULL,NULL,NULL,'[\"校园卡\",\"一卡通\",\"充值\",\"挂失\",\"补办\"]','[\"校园卡充值\", \"校园卡挂失\", \"一卡通办理\"]',104,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(124,'life_medical','医疗保健','kb_life','RAG_QA','校医院服务、医保报销、健康教育',NULL,NULL,NULL,'[\"校医院\",\"医保\",\"报销\",\"医疗\",\"体检\"]','[\"校医院营业时间\", \"医保报销\", \"体检安排\"]',104,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(125,'career_employ','就业手续','kb_career','RAG_QA','三方协议、档案转递、户口迁移、报到证',NULL,NULL,NULL,'[\"三方\",\"档案\",\"户口\",\"报到证\",\"就业手续\"]','[\"三方协议签订\", \"档案转递\", \"报到证办理\"]',105,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(126,'career_admission','升学指导','kb_career','RAG_QA','保研推免、考研指导、调剂流程',NULL,NULL,NULL,'[\"保研\",\"推免\",\"考研\",\"调剂\",\"复试\"]','[\"保研条件\", \"考研报名\", \"调剂流程\"]',105,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(127,'career_dispatch','派驻管理','kb_career','RAG_QA','学生派驻、实习基地、联合培养',NULL,NULL,NULL,'[\"派驻\",\"实习\",\"联合培养\"]','[\"派驻申请\", \"实习基地\", \"联合培养项目\"]',105,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(128,'research_project','科研项目','kb_research','RAG_QA','纵向项目、横向项目、科研项目申报、经费管理',NULL,NULL,NULL,'[\"科研项目\",\"纵向\",\"横向\",\"经费\",\"申报\"]','[\"科研项目申报\", \"经费管理办法\", \"横向项目\"]',106,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(129,'research_lab','实验室安全','kb_research','RAG_QA','实验室准入、安全培训、仪器设备管理',NULL,NULL,NULL,'[\"实验室\",\"安全\",\"准入\",\"仪器\",\"设备\"]','[\"实验室准入条件\", \"安全培训\", \"仪器预约\"]',106,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(130,'research_competition','学科竞赛','kb_research','RAG_QA','冯如杯、挑战杯、互联网+、大创项目',NULL,NULL,NULL,'[\"冯如杯\",\"挑战杯\",\"大创\",\"竞赛\",\"创新创业\"]','[\"冯如杯报名\", \"大创申请\", \"竞赛奖励\"]',106,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(131,'research_ip','知识产权','kb_research','RAG_QA','专利申请、著作权、成果转化、技术转让',NULL,NULL,NULL,'[\"专利\",\"知识产权\",\"成果转化\",\"技术转让\"]','[\"专利申请流程\", \"成果转化奖励\", \"知识产权保护\"]',106,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(132,'psy_counseling','心理咨询','kb_psy','RAG_QA','心理咨询预约、心理热线、情绪调节',NULL,NULL,NULL,'[\"心理咨询\",\"预约\",\"情绪\",\"压力\",\"热线\"]','[\"心理咨询预约\", \"心理热线\", \"情绪调节方法\"]',107,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(133,'psy_safety','校园安全','kb_psy','RAG_QA','消防安全、防诈骗、紧急求助、安全教育',NULL,NULL,NULL,'[\"消防\",\"诈骗\",\"安全\",\"求助\",\"报警\"]','[\"防诈骗指南\", \"消防演习\", \"紧急求助电话\"]',107,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(135,'admin_doc','公文与行政','kb_integrated','RAG_QA','公文处理、印信管理、档案管理、通讯录',NULL,NULL,NULL,'[\"公文\",\"印信\",\"档案\",\"通讯录\",\"公章\"]','[\"公文格式\", \"用印申请\", \"档案查询\"]',108,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(136,'org_structure','组织架构','kb_integrated','RAG_QA','学院部门设置、职能介绍、办事指南',NULL,NULL,NULL,'[\"部门\",\"职能\",\"办事\",\"组织架构\",\"办公室\"]','[\"教务处电话\", \"学工办职责\", \"行政部门\"]',108,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(137,'external_exchange','出国交流','kb_external','RAG_QA','交换项目、暑期学校、联合培养、访问学者',NULL,NULL,NULL,'[\"交换\",\"出国\",\"交流\",\"联合培养\",\"访问\"]','[\"交换项目申请\", \"暑期学校\", \"联合培养\"]',109,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(138,'external_visa','签证办理','kb_external','RAG_QA','因公出国签证、护照办理、出境手续',NULL,NULL,NULL,'[\"签证\",\"护照\",\"出境\",\"因公出国\"]','[\"签证办理流程\", \"护照申请\", \"因公出国手续\"]',109,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(139,'external_intl','留学生事务','kb_external','RAG_QA','留学生招生、学籍管理、生活服务',NULL,NULL,NULL,'[\"留学生\",\"国际学生\",\"招生\"]','[\"留学生招生\", \"留学生服务\", \"国际学生管理\"]',109,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(140,'manual_general','总则与行为规范','kb_integrated','RAG_QA','学生权利义务、行为准则、基本规范',NULL,NULL,NULL,'[\"总则\",\"行为规范\",\"权利\",\"义务\"]','[\"学生权利\", \"行为规范要求\"]',108,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(141,'manual_discipline','违纪处分','kb_integrated','RAG_QA','考试作弊、违纪行为、处分等级、申诉流程',NULL,NULL,NULL,'[\"违纪\",\"处分\",\"作弊\",\"警告\",\"记过\",\"开除\"]','[\"考试作弊怎么处理\", \"违纪处分等级\", \"处分申诉\"]',108,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(143,'manual_order','校园秩序','kb_integrated','RAG_QA','宿舍管理规定、校园活动秩序、安全管理',NULL,NULL,NULL,'[\"校园秩序\",\"宿舍规定\",\"安全管理\"]','[\"宿舍管理规定\", \"校园活动秩序\"]',108,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0),(147,'chitchat','日常闲聊','root','CHITCHAT','打招呼、日常聊天、简单问候','这是闲聊场景，请保持简洁友好回答。',NULL,NULL,'[\"你好\", \"在吗\", \"谢谢\", \"早上好\", \"晚安\", \"聊聊\"]','[\"你好\", \"在吗\", \"聊聊天\"]',NULL,NULL,NULL,NULL,1,'2026-04-11 20:50:30','2026-04-11 20:50:30',0);
/*!40000 ALTER TABLE `intent_node` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `knowledge`
--

DROP TABLE IF EXISTS `knowledge`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `knowledge` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '创建用户ID',
  `name` varchar(128) NOT NULL COMMENT '知识库名称',
  `description` varchar(512) DEFAULT NULL COMMENT '知识库描述',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `del_flag` tinyint(1) DEFAULT '0' COMMENT '删除标识 0：未删除 1：已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_name` (`user_id`,`name`) COMMENT '同一用户下知识库名唯一'
) ENGINE=InnoDB AUTO_INCREMENT=110 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `knowledge`
--

LOCK TABLES `knowledge` WRITE;
/*!40000 ALTER TABLE `knowledge` DISABLE KEYS */;
INSERT INTO `knowledge` VALUES (101,1,'academic_kb','教务教学库：包含学籍、选课、考试、培养方案、成绩管理等','2026-04-11 20:50:30','2026-04-11 20:50:30',0),(102,1,'affairs_kb','奖助事务库：包含奖学金、助学金、勤工助学、综合测评、评优评先等','2026-04-11 20:50:30','2026-04-11 20:50:30',0),(103,1,'finance_kb','财务资产库：包含差旅报销、劳务发放、采购合同、发票管理等','2026-04-11 20:50:30','2026-04-11 20:50:30',0),(104,1,'campus_life_kb','校园生活库：包含公寓管理、膳食服务、校车时刻、物业报修、校园卡等','2026-04-11 20:50:30','2026-04-11 20:50:30',0),(105,1,'career_kb','就业发展库：包含三方协议、档案转递、保研政策、就业指南、毕业生去向等','2026-04-11 20:50:30','2026-04-11 20:50:30',0),(106,1,'research_kb','科创科研库：包含科研经费、实验室安全、冯如杯、大创申请、知识产权等','2026-04-11 20:50:30','2026-04-11 20:50:30',0),(107,1,'psy_safety_kb','心理安全库：包含心理咨询预约、消防安全、校园秩序、反诈宣传、紧急求助等','2026-04-11 20:50:30','2026-04-11 20:50:30',0),(108,1,'integrated_kb','综合规章库：包含学生手册、公文处理、行政通讯录、印信管理、规章制度等','2026-04-11 20:50:30','2026-04-11 20:50:30',0),(109,1,'external_kb','外事交流库：包含出国交流、签证办理、外事接待、留学生事务、联合培养等','2026-04-11 20:50:30','2026-04-11 20:50:30',0);
/*!40000 ALTER TABLE `knowledge` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-04-12  0:10:58
