# AI School Counselor 技术文档（后端）

> 文档版本：v1.0  
> 更新时间：2026-02-22  
> 代码基线：当前工作区（包含未提交改动）  
> 项目路径：`/Users/yushuhao/Graduation/ai-school-counselor`

## 1. 项目定位

本项目是一个面向高校场景的 Agentic RAG 后端系统，目标是提供“辅导员问答 + 办事工具调用 + 文档知识库检索”的统一能力，核心场景包括：

- 校园政策问答（RAG）
- 办事流程处理（Tool Calling）
- 危机类语句识别与拦截
- 文档上传、解析、向量化、检索
- 对话历史、来源追踪、反馈回流

当前系统主入口为 `ChatController`，对外提供同步问答、SSE 流式问答、检索与反馈接口。

## 2. 技术栈与版本

| 领域 | 选型 | 版本/说明 |
| --- | --- | --- |
| 语言 | Java | 17 |
| Web | Spring Boot | 3.4.2 |
| AI SDK | Spring AI | 1.0.1 |
| AI Provider | Spring AI Alibaba DashScope | 1.0.0.2 |
| ORM | MyBatis-Plus | 3.5.9 |
| 数据库 | MySQL | 本地默认 `rag` 库 |
| 缓存/队列 | Redis + Redis Stream | 会话登录态、异步摄取任务 |
| 检索引擎 | Elasticsearch Java Client | 8.10.0 |
| 对象存储 | RustFS (S3 兼容) | 通过 AWS SDK v2 访问 |
| 文档解析 | Apache Tika | 2.9.1 |
| 分词 | HanLP | portable-1.8.6 |
| 构建 | Maven | `mvn clean package` |

## 3. 总体架构

系统按“请求路由层 -> 检索与生成层 -> 数据与基础设施层”组织：

1. 请求进入 Controller（`/api/rag/...`）。
2. 通过 Filter 做用户上下文注入与登录校验。
3. `ChatServiceImpl` 编排意图路由、检索、CRAG 评估、生成、缓存、持久化。
4. 文档上传走异步摄取链路（Redis Stream）。
5. 存储层落地 MySQL（业务数据）、ES（向量/文本索引）、RustFS（原文件）、Redis（缓存与任务流）。

## 4. 代码结构

| 路径 | 说明 |
| --- | --- |
| `src/main/java/org/buaa/rag/controller` | 接口入口层（聊天/文档/用户） |
| `src/main/java/org/buaa/rag/service` | 服务接口定义 |
| `src/main/java/org/buaa/rag/service/impl` | 核心业务实现 |
| `src/main/java/org/buaa/rag/service/ingestion` | 文档异步摄取生产者/消费者 |
| `src/main/java/org/buaa/rag/dao/entity` | MyBatis-Plus 实体 |
| `src/main/java/org/buaa/rag/dao/mapper` | Mapper |
| `src/main/java/org/buaa/rag/tool` | LLM、Embedding、RustFS、工具函数 |
| `src/main/java/org/buaa/rag/config` | Spring 配置与属性映射 |
| `src/main/resources` | `application.yml`、意图树、Prompt 模板、静态页面 |
| `datasource` | 初始化 SQL 与 ES mapping 示例 |

## 5. 核心模块设计

### 5.1 ChatService（主编排）

实现类：`ChatServiceImpl`

主要职责：

- 统一同步与流式问答
- 意图路由（危机、工具、RAG、澄清）
- 多意图拆分与合成回答
- 检索融合（原问 + 改写 + HyDE）
- CRAG 质量评估与 fallback 检索
- 语义缓存
- 对话历史与来源落库
- 用户反馈写回

关键常量（代码中硬编码）：

- 默认检索 `topK=5`，最大 `10`
- 低质量阈值 `0.25`
- 多意图合成 Prompt 独立模板

### 5.2 IntentRouter（混合路由）

实现类：`IntentRouterServiceImpl`

路由阶段：

1. 危机词拦截（`CRISIS_KEYWORDS`）
2. 关键词工具直达（请假/报修/成绩）
3. 意图树 Beam Search（Top-2，深度4）
4. 语义路由（`intent_patterns` 向量索引）
5. LLM 结构化分类兜底
6. 低置信度澄清

关键阈值：

- 意图树命中阈值：`0.6`
- 意图树澄清差值：`0.1`
- 语义直采阈值：`0.9`
- LLM 走 RAG 阈值：`0.5`

### 5.3 Retriever（检索与后处理）

实现类：

- `SmartRetrieverServiceImpl`
- `RetrievalPostProcessorServiceImpl`
- `QueryAnalysisServiceImpl`
- `AnswerValidatorImpl`

能力：

- 混合检索：kNN + BM25 + rescore
- 纯文本/纯向量检索降级
- Query Rewrite
- HyDE
- RRF 融合
- LLM 重排
- CRAG 决策（ANSWER/REFINE/CLARIFY/NO_ANSWER）
- 答案质检（OK/REFINE）
- 反馈分数回流影响排序

### 5.4 文档摄取（异步）

实现类：

- `DocumentServiceImpl`
- `DocumentIngestionStreamProducer`
- `DocumentIngestionStreamConsumer`

流程：

1. 上传文件并做 MIME 与扩展名校验。
2. 计算 MD5，按“用户 + MD5”去重。
3. 文件落 RustFS，文档记录写 MySQL（状态 pending）。
4. 投递 Redis Stream 任务。
5. 消费任务后进行 Tika 提取、文本清洗、智能切块、分段落库。
6. 批量向量化，构建 `IndexedContentDO` 批量写 ES。
7. 更新文档状态 completed/failed。

### 5.5 Tool Calling

实现类：

- `ToolServiceImpl`
- `CounselorTools`

工具白名单：

- `queryGrade`（成绩）
- `createLeaveDraft`（请假草稿）
- `createRepairTicket`（报修草稿）

策略：

- 优先走 Spring AI Function Calling
- 失败后走后端确定性回退逻辑

### 5.6 用户与鉴权

实现类：

- `UserServiceImpl`
- `RefreshTokenFilter`
- `LoginCheckFilter`

机制：

- 登录态缓存到 Redis
- `RefreshTokenFilter` 从请求头 `mail` + `token` 注入 `UserContext`
- `LoginCheckFilter` 对非白名单接口要求已登录

## 6. 端到端流程

### 6.1 同步问答流程（`POST /api/rag/chat/chat`）

1. 参数校验（message 不为空）。
2. 获取会话 ID。
3. 意图路由。
4. 危机则直接返回固定应急话术。
5. 多意图则拆分子问题并逐个执行。
6. RAG 路径下执行检索、重排、CRAG、生成、质检。
7. 写入会话历史与来源。
8. 返回 `response + sources`。

### 6.2 SSE 流式流程（`GET /api/rag/chat/stream`）

事件类型：

- 默认消息：增量文本 chunk
- `sources`：来源列表
- `messageId`：assistant 消息 ID
- `done`：结束
- `error`：异常

### 6.3 文档上传摄取流程（`POST /api/rag/document/upload`）

1. 文件合法性校验。
2. 入对象存储。
3. 文档元数据入库。
4. 入 Redis Stream。
5. 异步解析、切块、向量化、建索引。

## 7. 数据模型与存储

### 7.1 MySQL 表

初始化脚本：`datasource/database.sql`

核心表：

- `user`：用户信息
- `document`：文档元信息（状态、可见性）
- `text_segments`：文档切块文本
- `messages`：会话消息
- `message_sources`：回答引用来源
- `message_feedback`：评分反馈

### 7.2 Elasticsearch 索引

- 知识索引（默认 `knowledge`，由 `elasticsearch.index` 配置）
    - 字段：`sourceMd5`、`segmentNumber`、`textPayload`、`vectorEmbedding`
    - mapping 示例：`datasource/knowledge.json`
- 意图索引（默认 `intent_patterns`）
    - 字段：`intentCode`、`level1`、`level2`、`sample`、`vector`

### 7.3 Redis 键

| 类别 | Key 前缀 | 说明 |
| --- | --- | --- |
| 登录态 | `rag:user:login:` | token 缓存 |
| 用户信息 | `rag:user:info:` | 用户信息缓存 |
| 注册验证码 | `rag:user:register:code:` | 验证码 |
| 摄取任务流 | `rag:stream:document-ingestion` | Redis Stream |

## 8. 接口文档

### 8.1 通用响应结构

```json
{
  "code": "0",
  "message": null,
  "data": {}
}
```

说明：

- 成功码是字符串 `"0"`。
- 失败码来自 `UserErrorCodeEnum`、`ServiceErrorCodeEnum`、`DocumentErrorCodeEnum`。

### 8.2 鉴权约定

受保护接口需要携带请求头（由过滤器读取）：

- `mail`
- `token`

注意：当前代码中登录态 key 使用口径存在不一致（见“已知问题”章节）。

### 8.3 用户模块

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/rag/user` | 注册 |
| `GET` | `/api/rag/user/send-code?mail=` | 发送验证码 |
| `GET` | `/api/rag/user/{mail}` | 查用户 |
| `POST` | `/api/rag/user/login` | 登录 |
| `GET` | `/api/rag/user/check-login?username=&token=` | 检查登录 |
| `DELETE` | `/api/rag/user/logout?username=&token=` | 退出登录 |
| `PUT` | `/api/rag/user` | 更新用户信息 |

### 8.4 聊天模块

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/rag/chat/chat` | 同步问答 |
| `GET` | `/api/rag/chat/stream` | SSE 流式问答 |
| `GET` | `/api/rag/chat/search` | 检索测试 |
| `POST` | `/api/rag/chat/feedback` | 反馈评分 |

同步问答请求示例：

```json
{
  "message": "计算机学院保研条件是什么？",
  "userId": "1"
}
```

### 8.5 文档模块

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/rag/document/upload` | 上传文档（multipart） |
| `GET` | `/api/rag/document/list` | 当前用户文档列表 |
| `DELETE` | `/api/rag/document/{id}` | 删除文档 |

上传参数：

- `file`：文件本体
- `visibility`：`PUBLIC`/`PRIVATE`（服务端归一化为 `public/private`）

## 9. 配置说明

主配置文件：`src/main/resources/application.yml`

关键配置：

- `server.port=8000`
- `spring.datasource.*`：MySQL 连接
- `spring.data.redis.*`：Redis 连接
- `spring.ai.*`：DashScope chat/embedding
- `rustfs.*`：S3 兼容对象存储
- `elasticsearch.*`：ES 地址与索引名
- `rag.*`：Rewrite/HyDE/Fusion/Rerank/CRAG/语义缓存/摄取流
- `file.parsing.*`：上传体积、提取与清洗上限、切块长度

## 10. 本地启动指南

### 10.1 依赖服务

- MySQL（建议 8.x）
- Redis（建议 6.x+）
- Elasticsearch（建议 8.x）
- RustFS/MinIO（S3 兼容）

### 10.2 初始化

1. 执行 `datasource/database.sql` 初始化 MySQL。
2. 在 ES 创建知识索引并应用 `datasource/knowledge.json` mapping。
3. 按实际环境更新 `application.yml`。

### 10.3 运行命令

```bash
mvn spring-boot:run
```

打包命令：

```bash
mvn clean package -DskipTests
```

## 11. 测试现状

当前已有单元测试：

- `IntentRouterServiceImplTest`
- `QueryDecomposerImplTest`

当前缺少：

- Controller 层集成测试
- 文档摄取链路集成测试
- ES/Redis/RustFS 联调测试
- 鉴权过滤器回归测试

## 12. 已知问题与风险（基于当前工作区）

1. `mvn clean package -DskipTests` 当前失败。  
   失败原因：`src/main/java/org/buaa/rag/common/limit` 下限流源码缺失，而 `ChatController` 仍引用 `@RateLimit`。

2. 限流 Lua 脚本与历史切面协议不一致。  
   `src/main/resources/scripts/rate_limit.lua` 当前仅返回访问计数，不符合历史 `RateLimitAspect` 对“0/1 放行标志”的约定。

3. 登录态 key 口径不一致。  
   `UserServiceImpl.login` 以 `username` 写入 `rag:user:login:*`，`RefreshTokenFilter` 以 `mail` 读取，易导致已登录用户无法通过鉴权。

4. 静态页面接口路径与后端不一致。  
   `src/main/resources/static/js/script.js` 使用 `/api/chat`、`/api/documents`、`/api/upload` 等路径与 `code===200` 约定，和当前后端 `/api/rag/...` + 成功码 `"0"` 不一致。

5. 配置文件存在敏感信息直写风险。  
   建议将 AI API Key、数据库密码、对象存储密钥统一迁移为环境变量。

6. 数据库字段与实体存在类型口径差异。  
   例如 `messages.user_id` 在 SQL 中为 bigint，而实体 `MessageDO.userId` 为 `String`；`message_feedback.user_id` 在 SQL 中为 varchar，而实体为 `Long`。

## 13. 建议迭代路线

1. 先恢复限流源码并修正 Lua 协议，保证项目可 clean build。
2. 统一登录态 key 设计（建议统一用 `mail` 或 `userId`）。
3. 修复静态页面 API 路径与返回码适配。
4. 补齐 Controller + 鉴权 + 摄取链路集成测试。
5. 完成密钥外置与多环境配置分层（dev/test/prod）。  

