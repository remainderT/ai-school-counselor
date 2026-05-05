# RAG 在线链路详解

> 本文档详细描述 `ai-school-counselor` 项目中 RAG（检索增强生成）在线问答链路的完整架构、数据流向、核心组件及其交互关系。

---

## 目录

1. [架构总览](#1-架构总览)
2. [请求入口层](#2-请求入口层)
3. [管道编排层 — StreamChatPipeline](#3-管道编排层--streamchatpipeline)
4. [阶段一：会话准备 — prepareSession](#4-阶段一会话准备--preparesession)
5. [阶段二：对话记忆加载 — loadConversationHistory](#5-阶段二对话记忆加载--loadconversationhistory)
6. [阶段三：查询预处理 — rewriteQuery](#6-阶段三查询预处理--rewritequery)
7. [阶段四：意图识别 — resolveIntents](#7-阶段四意图识别--resolveintents)
8. [阶段五：路由执行 — RouteExecutionCoordinator](#8-阶段五路由执行--routeexecutioncoordinator)
9. [检索引擎 — MultiChannelRetrievalEngine](#9-检索引擎--multichannelretrievalengine)
10. [检索后处理链](#10-检索后处理链)
11. [Rerank 精排体系](#11-rerank-精排体系)
12. [CRAG 检索质量评估](#12-crag-检索质量评估)
13. [答案生成 — RagPromptService](#13-答案生成--ragpromptservice)
14. [工具调用 — ToolService](#14-工具调用--toolservice)
15. [LLM 调用统一封装 — LlmChat](#15-llm-调用统一封装--llmchat)
16. [全链路追踪 — RagTrace](#16-全链路追踪--ragtrace)
17. [SSE 流式推送机制](#17-sse-流式推送机制)
18. [端到端数据流图](#18-端到端数据流图)
19. [配置项速查](#19-配置项速查)

---

## 1. 架构总览

整个在线链路采用**管道（Pipeline）+ 路由协调器（Coordinator）**架构，核心流程如下：

```
用户消息 (SSE)
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│                  ConversationController                      │
│                 (SSE 接入 / 搜索接口)                         │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                       ChatFacade                             │
│           (实现 ChatService, 管理流式任务生命周期)              │
└────────────────────────┬────────────────────────────────────┘
                         │ CompletableFuture.runAsync
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                   StreamChatPipeline                         │
│                    (5 阶段管道编排)                            │
│                                                              │
│  ┌──────────┐  ┌───────────┐  ┌──────────┐  ┌────────────┐ │
│  │1.会话准备 │→│2.记忆加载  │→│3.查询重写 │→│4.意图识别   │ │
│  └──────────┘  └───────────┘  └──────────┘  └─────┬──────┘ │
│                                                    │        │
│                                          ┌─────────┴──────┐ │
│                                          │ 5.路由执行       │ │
│                                          │(Coordinator)    │ │
│                                          └─────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                         │
            ┌────────────┼────────────┐
            ▼            ▼            ▼
     ┌──────────┐ ┌──────────┐ ┌──────────┐
     │ 检索引擎  │ │ 工具调用  │ │ 直接问答  │
     │(RAG路由) │ │(TOOL路由)│ │(CHAT路由)│
     └──────────┘ └──────────┘ └──────────┘
            │
            ▼
   ┌─────────────────┐
   │ 后处理 + CRAG    │
   │(去重/精排/评估)  │
   └────────┬────────┘
            ▼
   ┌─────────────────┐
   │ 答案生成 (流式)   │
   │ RagPromptService │
   └────────┬────────┘
            ▼
     SSE 推送给前端
```

**关键设计原则：**

- **多层短路**：歧义引导、纯闲聊等场景直接短路返回，不经过检索环节
- **多通道检索**：意图定向检索 + 向量全局检索并行执行
- **DashScope 精排**：Rerank 直接调用 DashScope Rerank API（gte-rerank），失败时截断兜底
- **低分过滤**：Rerank 后通过 `ScoreFilterPostProcessor` 过滤低于 `min-acceptable-score`（0.4）阈值的结果
- **语义缓存**：通过语义相似度缓存（`min-similarity: 0.92`）避免重复检索
- **CRAG 质量控制**：检索后由 LLM 评估质量，决定是否需要 Refine/Clarify
- **全链路追踪**：通过 AOP 注解自动记录各环节耗时与状态

---

## 2. 请求入口层

### 2.1 ConversationController

**文件位置：** `src/main/java/org/buaa/rag/controller/ConversationController.java`

提供以下在线接口：

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 会话列表 | `GET` | `/api/rag/conversations/sessions` | 获取用户的会话列表 |
| 创建会话 | `POST` | `/api/rag/conversations/sessions` | 创建新会话 |
| 删除会话 | `DELETE` | `/api/rag/conversations/sessions/{sessionId}` | 删除指定会话 |
| 重命名会话 | `PUT` | `/api/rag/conversations/sessions/{sessionId}` | 修改会话标题 |
| 历史消息 | `GET` | `/api/rag/conversations/history` | 获取会话历史消息 |
| 流式问答 | `GET` | `/api/rag/conversations/stream` | SSE (Server-Sent Events) 流式推送 |
| 独立搜索 | `GET` | `/api/rag/conversations/search` | 同步检索，返回 Top-K 结果 |

**流式问答接口参数：**

```
message          - 用户输入的问题（唯一请求参数）
```

`userId` 通过 `UserContext.resolvedUserId()` 从请求上下文中自动获取，无需显式传入。会话 ID 由系统内部自动管理。

接口返回 `SseEmitter` 对象，通过 SSE 协议向前端逐 token 推送回答内容。

### 2.2 ChatFacade

**文件位置：** `src/main/java/org/buaa/rag/core/online/chat/ChatFacade.java`

`ChatFacade` 是 `ChatService` 接口的唯一实现类，承担以下职责：

1. **创建 SSE 连接**：构造 `SseEmitter`，设置超时时间和回调
2. **构造事件处理器**：创建 `SseStreamChatEventHandler` 封装 SSE 推送逻辑
3. **提交异步任务**：通过 `CompletableFuture.runAsync` 将整个管道执行放入线程池
4. **管理取消句柄**：通过 `StreamTaskManager` 绑定 `taskId`，支持流式任务取消
5. **异常兜底**：在异步任务中捕获所有异常，通过 SSE 推送错误事件

```java
// 核心流程伪代码
public SseEmitter handleChatStream(String message, Long userId) {
    SseEmitter emitter = new SseEmitter(timeout);
    String taskId = generateTaskId();
    SseStreamChatEventHandler handler = new SseStreamChatEventHandler(emitter);

    CompletableFuture.runAsync(() -> {
        streamChat(message, userId, taskId, handler);  // @RagTraceRoot
    }, executor);

    streamTaskManager.register(taskId, handler);
    return emitter;
}
```

---

## 3. 管道编排层 — StreamChatPipeline

**文件位置：** `src/main/java/org/buaa/rag/core/online/chat/StreamChatPipeline.java`

`StreamChatPipeline` 是整个在线链路的核心编排器，将一次用户问答分解为 **5 个顺序执行的阶段**，并根据中间结果决定走短路还是正常路径。

### 3.1 管道执行流程

```
execute(PipelineContext ctx)
    │
    ├─► Stage 1: prepareSession()        — 会话初始化
    ├─► Stage 2: loadConversationHistory() — 加载对话记忆
    ├─► Stage 3: rewriteQuery()           — 查询重写与拆分 (LLM)
    ├─► Stage 4: resolveIntents()         — 意图识别与决策 (LLM)
    ├─► buildRoutingPlan()                — 构建路由计划
    │
    ├─► [短路1] 歧义引导 → 直接推送澄清问题，结束
    ├─► [短路2] 纯系统意图(闲聊) → streamSystemDirectResponse()，结束
    │
    └─► [正常路径] RouteExecutionCoordinator.execute()
            │
            ├─► 单意图路径 → executeSingleIntentRoute()
            └─► 多意图路径 → executeMultiIntentRoute()
```

### 3.2 Context

管道上下文对象（内部类 `StreamChatPipeline.Context`），在各阶段之间传递数据：

| 字段 | 类型 | 说明 |
|------|------|------|
| `message` | `String` | 用户原始输入 |
| `rewrittenQuery` | `String` | 经 LLM 改写后的查询 |
| `rewriteResult` | `QueryRewriteResult` | 查询改写结果（含子查询列表） |
| `subQueryIntents` | `List<SubQueryIntent>` | 每个子查询的意图决策 |
| `conversationHistory` | `List<Map<String, String>>` | 对话历史消息（key-value 形式） |
| `sessionId` | `String` | 会话 ID |
| `assistantMessageId` | `Long` | 助手消息占位记录 ID |
| `taskId` | `String` | 流式任务唯一标识 |
| `generatedTitle` | `String` | 异步生成的会话标题 |
| `callback` | `StreamChatCallback` | SSE 推送回调 |
| `cancelHandle` | `StreamCancellationHandle` | 流式取消句柄 |

### 3.3 短路机制

管道设计了两条短路路径，避免不必要的检索和 LLM 调用：

**短路1 — 歧义引导**：当意图识别阶段返回 `CLARIFY` 类型的决策时，说明用户问题含糊不清，系统直接通过 SSE 推送引导性问题（如"请问您想了解的是...？"），不进入检索环节。

**短路2 — 纯系统意图**：当所有子查询都被识别为系统意图（闲聊 `SYSTEM_CHAT`），直接调用 LLM 生成回复，跳过检索流程。

---

## 4. 阶段一：会话准备 — prepareSession

### 4.1 核心逻辑

1. **获取或创建会话**：根据 `conversationId` 查找已有会话，若为空则创建新会话
2. **追加用户消息**：将用户输入持久化到消息表
3. **生成会话标题**：首次对话时异步调用 LLM 为会话生成简短标题
4. **创建助手占位消息**：在消息表中预先插入一条空的助手消息记录，后续流式生成完毕后回填内容

### 4.2 涉及组件

| 组件 | 说明 |
|------|------|
| `ConversationService` | 会话 CRUD 管理 |
| `MessageMapper` | 消息持久化 (MyBatis-Plus) |
| `LlmChat` | 生成会话标题 |

---

## 5. 阶段二：对话记忆加载 — loadConversationHistory

### 5.1 核心逻辑

**文件位置：** `src/main/java/org/buaa/rag/core/online/memory/ConversationMemoryServiceImpl.java`

对话记忆采用 **近期消息 + 远期摘要** 的混合策略：

```
┌─────────────────────────────────────────────────┐
│               ConversationMemoryServiceImpl       │
│                                                   │
│  ┌────────────────┐   ┌─────────────────────┐    │
│  │  近期原始消息    │   │  远期对话摘要        │    │
│  │ (最近 N 轮)     │   │ (LLM 压缩的摘要)    │    │
│  └───────┬────────┘   └──────────┬──────────┘    │
│          │                       │                │
│          └───────────┬───────────┘                │
│                      ▼                            │
│          以 XML 标签格式注入 System Prompt          │
│   <conversation-summary>...</conversation-summary>│
└─────────────────────────────────────────────────┘
```

### 5.2 并行加载策略

`loadContextParallel()` 方法使用 `CompletableFuture` 并行加载两类数据：

- **线程 A**：从数据库加载最近 N 轮对话（N 由 `rag.memory.history-keep-turns` 配置，默认 4 轮即 8 条消息）
- **线程 B**：从数据库加载已有的对话摘要

两个任务并行完成后合并组装上下文。

### 5.3 异步摘要压缩

当对话轮次超过阈值（`rag.memory.summary-start-turns`，默认 8 轮）时，系统异步触发 **增量摘要压缩**：

1. 使用 Redis 分布式锁（`SETNX`）确保同一会话不会并发压缩
2. 至少需要 `min-delta-messages`（默认 4）条新消息才触发增量摘要
3. 将超出"近期窗口"的历史消息通过 LLM 压缩为摘要（最大 `summary-max-chars: 320` 字符）
4. 持久化新摘要到 `message_summary` 表
5. 使用 `conversation-summary.st` 提示词模板

---

## 6. 阶段三：查询预处理 — rewriteQuery

### 6.1 核心逻辑

**文件位置：** `src/main/java/org/buaa/rag/core/online/rewrite/QueryRewriteAndSplitService.java`

查询预处理阶段完成两个任务：**指代消歧改写** 和 **多意图拆分**，通过一次 LLM 调用同时完成。

```
用户输入: "第一个怎么选课，第二个学分绩点怎么算"
    │
    ├─► 同义词归一化 (QueryTermMappingService)
    │       例: "绩点" → "学分绩点"
    │
    ├─► 快速路径判断
    │       若不需要改写（无历史 + 单一查询）→ 跳过 LLM
    │
    └─► LLM 改写 + 拆分 (query-rewrite-and-split.st)
            │
            ├─► rewritten: "如何选课？学分绩点的计算方法是什么？"
            └─► subQueries: ["如何选课", "学分绩点的计算方法是什么"]
```

### 6.2 同义词归一化

**文件位置：** `src/main/java/org/buaa/rag/core/online/rewrite/QueryTermMappingService.java`

在 LLM 改写之前，先用规则表做同义词映射。映射表在 `QueryTermMappingService` 中维护（代码内置或从配置加载），用于将用户输入中的非标准术语归一化为标准术语。

### 6.3 提示词模板

使用 `query-rewrite-and-split.st`（StringTemplate4 格式），输入包含：

- 用户原始查询
- 近期对话历史
- 同义词归一化后的查询

输出 JSON 格式的改写结果和子查询列表。

### 6.4 快速路径

当满足以下条件时跳过 LLM 调用，直接用规则拆分：

- 无对话历史（首次对话）
- 查询中不存在指代词
- 查询预处理模块已启用（`rag.query-preprocess.enabled: true`）

---

## 7. 阶段四：意图识别 — resolveIntents

### 7.1 整体架构

意图识别由三个服务协作完成：

```
┌──────────────────────────────────────────────────────────┐
│                IntentResolutionService                     │
│            (对每个子查询并行调度意图路由)                     │
│                                                           │
│   subQuery[0] ──► IntentRouterService.rankCandidates()    │
│   subQuery[1] ──► IntentRouterService.rankCandidates()    │
│   subQuery[2] ──► IntentRouterService.rankCandidates()    │
│        ...                                                │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│                  IntentRouterService                       │
│                                                           │
│  1. 获取意图树叶子节点 (IntentTreeSnapshotService)          │
│  2. LLM 分类 (intent-tree-classifier.st)                  │
│  3. 歧义确认 (AmbiguityLLMChecker)                        │
│  4. 生成 IntentDecision                                    │
└──────────────────────────────────────────────────────────┘
```

### 7.2 意图树结构

**文件位置：** `src/main/java/org/buaa/rag/core/online/intent/IntentTreeSnapshotService.java`

意图树从**数据库**（`intent_node` 表）加载，并通过 **Redis 缓存**加速访问。结构为多级树形，当前预置 9 个知识库域：

```
root
├── 教务教学 (academic_kb)
│   ├── 学籍管理
│   ├── 选课指导
│   ├── 考试安排
│   ├── 培养方案
│   └── 成绩查询
├── 奖助事务 (affairs_kb)
│   ├── 奖学金
│   ├── 助学金
│   └── 综合素质测评
├── 财务资产 (finance_kb)
├── 校园生活 (campus_life_kb)
│   ├── 宿舍管理
│   ├── 食堂餐饮
│   └── 校园卡
├── 就业发展 (career_kb)
├── 科创科研 (research_kb)
├── 心理安全 (psy_safety_kb)
├── 综合规章 (integrated_kb)
├── 外事交流 (external_kb)
└── SYSTEM_CHAT (系统闲聊)
```

每个节点 (`IntentNode`) 在数据库中包含以下字段：

| 字段 | 说明 |
|------|------|
| `nodeId` | 节点唯一标识 |
| `nodeName` | 节点名称 |
| `parentId` | 父节点 ID |
| `type` | 节点类型枚举（`GROUP` / `API_ACTION` / `CHITCHAT` 等） |
| `description` | 节点描述（用于 LLM 分类） |
| `knowledgeBaseId` | 关联的知识库 ID（通过知识库间接关联 Milvus 集合） |
| `keywords` / `keywordsJson` | 关键词列表 |
| `examples` / `examplesJson` | 示例问题列表 |
| `promptTemplate` | Prompt 模板（可选） |
| `promptSnippet` | Prompt 片段（可选） |
| `actionService` | 工具调用服务标识（仅 `API_ACTION` 类型） |
| `mcpToolId` | MCP 工具 ID（可选） |
| `topK` | 该意图的检索 Top-K 覆盖值（可选） |

### 7.3 LLM 意图分类

使用 `intent-tree-classifier.st` 提示词模板，将所有叶子节点的 `intentName + description` 作为候选列表，让 LLM 选择最匹配的意图。

LLM 返回的 JSON 包含：

```json
{
  "intentId": "academic_credit",
  "confidence": 0.92,
  "reasoning": "用户询问学分绩点计算方法"
}
```

### 7.4 歧义检测

**文件位置：** `src/main/java/org/buaa/rag/core/online/intent/AmbiguityLLMChecker.java`

当 LLM 分类的置信度低于阈值（`rag.intent-guidance.ambiguity-ratio`，默认 0.88）时，触发歧义确认：

1. 调用 LLM 判断查询是否真的模糊
2. 若确认歧义 → 生成 `CLARIFY` 决策，附带引导问题
3. 若 LLM 认为不模糊 → 仍采用原分类结果

### 7.5 IntentDecision 输出

每个子查询经过意图识别后输出一个 `SubQueryIntent`，其中包含意图决策信息：

```java
public class SubQueryIntent {
    String subQuery;                       // 子查询内容
    List<IntentDecision> candidates;       // 候选意图列表（按置信度降序）
    // IntentDecision 包含：
    //   IntentNode matchedIntent  — 匹配的意图节点
    //   double confidence         — 置信度
    //   String routeType          — 路由类型
}
```

---

## 8. 阶段五：路由执行 — RouteExecutionCoordinator

**文件位置：** `src/main/java/org/buaa/rag/core/online/chat/RouteExecutionCoordinator.java`

路由执行协调器根据意图决策的数量分为两条路径：

### 8.1 单意图路径 — executeSingleIntentRoute

当只有一个子查询（或所有子查询指向同一意图）时走此路径：

```
IntentDecision
    │
    ├─► [CLARIFY] → 直接推送澄清问题
    │
    ├─► [ROUTE_TOOL] → ToolService.execute()
    │       └─► 调用工具 → 格式化结果 → 流式推送
    │
    ├─► [ROUTE_CHAT] → RagPromptService.generateRagAnswerWithoutReferences()
    │       └─► 纯 LLM 对话（无检索）→ 流式推送
    │
    └─► [ROUTE_RAG] → RAG 完整流程
            │
            ├─► 1. SubQueryRetrievalService.retrieveByStrategy()
            │       └─► MultiChannelRetrievalEngine.retrieve()
            │               ├─► IntentDirectedSearchChannel
            │               └─► VectorGlobalSearchChannel
            │
            ├─► 2. 后处理链
            │       ├─► DeduplicationPostProcessor (去重)
            │       ├─► RerankPostProcessor (精排)
            │       ├─► ScoreFilterPostProcessor (低分过滤)
            │       └─► TopKLimitPostProcessor (截断)
            │
            ├─► 3. RetrievalPostProcessorService.evaluate() (CRAG)
            │       └─► 输出: ANSWER / REFINE / CLARIFY / NO_ANSWER
            │
            ├─► [REFINE] → 降级重新检索
            │       └─► SubQueryRetrievalService.fallbackRetrieval()
            │
            └─► 4. RagPromptService.generateSingleIntentStructuredAnswer()
                    └─► LLM 流式生成最终答案
```

### 8.2 多意图路径 — executeMultiIntentRoute

当存在多个子查询且指向不同意图时走此路径：

```
List<IntentDecision>
    │
    ├─► 并行执行每个子查询任务 (CompletableFuture.allOf)
    │       │
    │       ├─► subQuery[0] → executeSubQueryTask()
    │       │       ├─► [TOOL] → 工具调用
    │       │       ├─► [CLARIFY] → 返回澄清文本
    │       │       ├─► [CHAT] → LLM 直答
    │       │       └─► [RAG] → 检索 + CRAG + 后处理
    │       │
    │       ├─► subQuery[1] → executeSubQueryTask()
    │       └─► subQuery[N] → executeSubQueryTask()
    │
    ├─► 收集所有子查询结果
    │
    ├─► FastPath 判断
    │       若所有子查询结果都可拼接 → stitchFastPathResponse()
    │       直接拼接各子查询答案 → 流式推送
    │
    └─► 综合生成
            └─► RagPromptService.generateMultiIntentAnswer()
                    └─► 使用 chat-multi-intent-synthesis.st 模板
                    └─► LLM 将多个子查询的检索结果综合为一个连贯回答
                    └─► 流式推送
```

---

## 9. 检索引擎 — MultiChannelRetrievalEngine

**文件位置：** `src/main/java/org/buaa/rag/core/online/retrieval/MultiChannelRetrievalEngine.java`

### 9.1 多通道架构

检索引擎采用可插拔的多通道设计，所有通道实现 `SearchChannel` 接口并行执行：

```
MultiChannelRetrievalEngine
    │
    ├─► [通道1] IntentDirectedSearchChannel
    │       (意图定向检索 — 在意图关联的集合中检索)
    │
    └─► [通道2] VectorGlobalSearchChannel
            (向量全局检索 — 在全局向量库中检索)
    │
    └─► 合并各通道结果 → 进入后处理链
```

### 9.2 SearchChannel 接口

```java
public interface SearchChannel {
    String channelId();                      // 通道唯一标识
    String description();                    // 通道描述
    String channelType();                    // 通道类型
    int dispatchOrder();                     // 调度顺序（影响结果权重）
    boolean isApplicable(SearchContext ctx);  // 是否适用于当前查询上下文
    SearchChannelResult fetch(SearchContext ctx);  // 执行检索
}
```

### 9.3 IntentDirectedSearchChannel — 意图定向检索

**文件位置：** `src/main/java/org/buaa/rag/core/online/retrieval/channel/IntentDirectedSearchChannel.java`

基于意图识别结果，在意图节点关联的知识库对应的 Milvus 集合中进行检索：

1. 通过意图节点的 `knowledgeBaseId` 查找关联的知识库，进而确定目标 Milvus 集合
2. 调用 `SmartRetrieverService.retrieveScoped()` 在指定集合中检索
3. 底层使用 `MilvusRetrieverService` 进行向量相似度检索
4. 同时调用 ES 进行文本关键词检索
5. 融合向量检索和文本检索的结果（RRF 融合策略）

### 9.4 VectorGlobalSearchChannel — 向量全局检索

**文件位置：** `src/main/java/org/buaa/rag/core/online/retrieval/channel/VectorGlobalSearchChannel.java`

不依赖意图，在全局向量库中进行检索，作为补充通道：

1. 调用 `SmartRetrieverService.retrieveVectorOnly()` 在全局集合中检索
2. 使用 DashScope Embedding 将查询编码为向量
3. 在 Milvus 中执行 ANN（近似最近邻）检索

### 9.5 SmartRetrieverService — 智能检索服务

**文件位置：** `src/main/java/org/buaa/rag/core/online/retrieval/SmartRetrieverService.java`

统一封装 ES 文本检索和 Milvus 向量检索，支持多种检索策略：

| 方法 | 说明 |
|------|------|
| `retrieve()` | ES + Milvus 双路检索，RRF 融合 |
| `retrieveScoped()` | 在指定集合中的限定范围检索 |
| `retrieveVectorOnly()` | 仅向量检索 |

**RRF (Reciprocal Rank Fusion) 融合公式：**

```
RRF_score(d) = Σ 1 / (k + rank_i(d))
```

其中 `k` 为常数（默认 60），`rank_i(d)` 是文档在第 i 路检索中的排名。

### 9.6 MilvusRetrieverService — 向量检索

**文件位置：** `src/main/java/org/buaa/rag/core/online/retrieval/MilvusRetrieverService.java`

直接调用 Milvus SDK 执行向量检索：

1. 使用 DashScope `text-embedding-v4` 模型将查询编码为向量
2. 在 Milvus 中按 `COSINE`（余弦）相似度搜索（HNSW 索引，`M=48, efConstruction=200, searchEf=128`）
3. 返回 Top-K 候选文档片段

---

## 10. 检索后处理链

### 10.1 后处理器管道

后处理链基于 `SearchResultPostProcessor` 接口，按 `stage()` 排序依次执行：

```
检索原始结果
    │
    ├─► [stage=1]   DeduplicationPostProcessor  — 跨通道去重
    ├─► [stage=20]  RerankPostProcessor          — 精排重排序
    ├─► [stage=50]  ScoreFilterPostProcessor     — 低分过滤
    └─► [stage=100] TopKLimitPostProcessor       — 截断 Top-K
    │
    ▼
后处理结果 List<RetrievalMatch>
```

### 10.2 DeduplicationPostProcessor — 去重

**文件位置：** `src/main/java/org/buaa/rag/core/online/retrieval/postprocessor/DeduplicationPostProcessor.java`

跨通道去重策略：

1. 以文档片段的 `chunkId` 作为去重键
2. 当同一片段出现在多个通道结果中时，保留**加权分数最高**的那条
3. 权重计算考虑通道优先级：`finalScore = rawScore × channelWeight`

### 10.3 RerankPostProcessor — 精排

**文件位置：** `src/main/java/org/buaa/rag/core/online/retrieval/postprocessor/RerankPostProcessor.java`

调用 `RoutingRerankService` 对去重后的候选列表进行精排重排序（详见第 11 节）。

### 10.4 ScoreFilterPostProcessor — 低分过滤

**文件位置：** `src/main/java/org/buaa/rag/core/online/retrieval/postprocessor/ScoreFilterPostProcessor.java`

精排完成后，过滤掉相关度分数低于 `rag.retrieval.min-acceptable-score`（默认 0.4）阈值的结果。

- **stage = 50**：位于精排（stage=20）之后、截断（stage=100）之前
- 过滤条件：`relevanceScore != null && relevanceScore >= threshold`
- 作用：避免 0 分或极低分片段混入上下文干扰 LLM 回答质量
- 通过 `@Component` 自动注入，无需手动注册

### 10.5 TopKLimitPostProcessor — 截断

**文件位置：** `src/main/java/org/buaa/rag/core/online/retrieval/postprocessor/TopKLimitPostProcessor.java`

简单截断，保留过滤后的前 K 条结果（K 由 `rag.retrieval.top-k` 配置）。

---

## 11. Rerank 精排体系

### 11.1 架构

Rerank 精排采用 **DashScope 单通道** 架构，直接委托 `DashScopeRerankClient` 完成候选文档精排，失败时截断兜底：

```
RoutingRerankService
    │
    ├─► DashScopeRerankClient
    │       调用 DashScope Rerank API (gte-rerank 模型)
    │       返回按 relevance_score 降序排列的结果
    │
    └─► [异常兜底] 直接截断返回前 topN 条
```

**文件位置：**

| 组件 | 文件 |
|------|------|
| `RoutingRerankService` | `src/main/java/org/buaa/rag/core/online/rerank/RoutingRerankService.java` |
| `DashScopeRerankClient` | `src/main/java/org/buaa/rag/core/online/rerank/DashScopeRerankClient.java` |

### 11.2 RoutingRerankService

**文件位置：** `src/main/java/org/buaa/rag/core/online/rerank/RoutingRerankService.java`

精排入口服务，逻辑简洁：

1. 候选列表为空或仅 1 条时直接返回，跳过精排
2. 委托 `DashScopeRerankClient.rerank()` 执行精排
3. 若 DashScope API 调用抛异常，降级为直接截断返回前 `topN` 条

### 11.3 DashScopeRerankClient

**文件位置：** `src/main/java/org/buaa/rag/core/online/rerank/DashScopeRerankClient.java`

调用 DashScope Rerank API（百炼 text-rerank 接口）：

- **模型**：`gte-rerank`（可通过 `rag.rerank.dashscope.model` 配置）
- **输入**：`(query, List<document_text>)`
- **输出**：每个文档的 `index` + `relevance_score`，按相关性降序排列
- **超时**：API 调用超时 15 秒（`rag.rerank.dashscope.timeout-seconds`）
- **去重**：调用前按 `matchKey()` 对候选文本去重，避免重复文本浪费 API 配额
- **补充**：当 API 返回结果不足 `topN` 时，用未参与精排的剩余候选补齐

---

## 12. CRAG 检索质量评估

### 12.1 核心逻辑

**文件位置：** `src/main/java/org/buaa/rag/core/online/retrieval/postprocessor/RetrievalPostProcessorService.java`

CRAG (Corrective Retrieval Augmented Generation) 在检索后、答案生成前对检索质量进行评估：

```
检索结果 List<RetrievalMatch>
    │
    └─► evaluate(query, matches)
            │
            └─► LLM 评估 (retrieval-crag.st 模板)
                    │
                    ├─► ANSWER    — 检索质量充分，可直接生成答案
                    ├─► REFINE    — 检索质量不足，需降级重检索
                    ├─► CLARIFY   — 无法回答，需向用户澄清
                    └─► NO_ANSWER — 确定无法回答该问题
```

### 12.2 CragDecision 输出

```java
public class CragDecision {
    String action;        // ANSWER / REFINE / CLARIFY / NO_ANSWER
    String reasoning;     // LLM 给出的判断理由
    String clarifyHint;   // 澄清提示（仅 CLARIFY 时有值）
}
```

### 12.3 REFINE 降级策略

当 CRAG 评估为 `REFINE` 时，触发 `SubQueryRetrievalService.fallbackRetrieval()`：

1. 放宽检索范围（扩大集合范围或降低相似度阈值）
2. 增加 Top-K 数量
3. 用扩展后的结果重新进入后处理链
4. 若仍不足，降级为 `NO_ANSWER`

---

## 13. 答案生成 — RagPromptService

**文件位置：** `src/main/java/org/buaa/rag/core/online/chat/RagPromptService.java`

### 13.1 职责

RagPromptService 负责组装最终 Prompt 并调用 LLM 生成答案，支持以下生成模式：

| 方法 | 场景 | 模板 |
|------|------|------|
| `generateSingleIntentStructuredAnswer()` | 单意图 RAG 回答 | `rag-single-intent.st` |
| `generateMultiIntentAnswer()` | 多意图综合回答 | `rag-multi-intent.st` + `chat-multi-intent-synthesis.st` |
| `generateRagAnswerWithoutReferences()` | 纯对话（无检索） | 系统规则 + 对话历史 |
| `streamSystemDirectResponse()` | 系统闲聊 | `system-rules.st` |

### 13.2 Prompt 组装结构

以单意图 RAG 回答为例，最终 Prompt 结构：

```
┌─────────────────────────────────────────────────┐
│ System Message                                   │
│                                                   │
│ [system-rules.st]                                │
│ - 角色定义（AI 学业顾问）                          │
│ - 回答规则和约束                                   │
│                                                   │
│ [conversation-summary]（若有）                    │
│ <conversation-summary>                           │
│   之前讨论过选课相关问题...                        │
│ </conversation-summary>                          │
│                                                   │
│ [context-format.st]                              │
│ <knowledge-context>                              │
│   [检索到的文档片段1]                              │
│   [检索到的文档片段2]                              │
│   ...                                            │
│ </knowledge-context>                             │
│                                                   │
│ [rag-single-intent.st]                           │
│ - RAG 回答要求（引用来源、结构化等）                │
└─────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────┐
│ Conversation History (近期 N 轮对话)              │
│   User: ...                                      │
│   Assistant: ...                                 │
│   ...                                            │
└─────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────┐
│ User Message                                     │
│   改写后的用户查询                                 │
└─────────────────────────────────────────────────┘
```

### 13.3 上下文格式化

使用 `context-format.st` 模板将检索到的文档片段格式化为结构化文本，每个片段包含：

- 文档标题
- 片段内容
- 来源信息（文件名、页码等）

### 13.4 流式生成

答案生成全程使用流式（Streaming）模式：

1. 调用 `LlmChat.streamResponseWithHandle()`
2. LLM 逐 token 返回
3. 通过 `StreamChatCallback` → `SseStreamChatEventHandler` → `SseEmitter` 推送到前端
4. 支持中途取消（用户关闭页面时通过 `StreamCancellationHandle` 中止）

---

## 14. 工具调用 — ToolService

**文件位置：** `src/main/java/org/buaa/rag/core/online/tool/ToolService.java`

当意图路由类型为 `ROUTE_TOOL` 时，进入工具调用分支：

### 14.1 可用工具

工具定义在 `CounselorTools` 中，当前支持：

| 工具 | 方法 | 功能 |
|------|------|------|
| 成绩查询 | `queryGrade(studentId)` | 查询学生成绩信息 |
| 请假申请 | `createLeaveDraft(userId, startTime, endTime, reason)` | 创建请假草稿 |
| 报修工单 | `createRepairTicket(userId, dormitory, issue)` | 提交宿舍报修工单 |

### 14.2 执行流程

```
ToolService.execute(query, intentDecision)
    │
    ├─► 1. 使用 tool-agent.st 模板让 LLM 决定调用哪个工具及参数
    ├─► 2. 解析 LLM 返回的工具调用指令
    ├─► 3. 执行工具方法，获取结果
    └─► 4. 将工具结果格式化后返回
```

---

## 15. LLM 调用统一封装 — LlmChat

**文件位置：** `src/main/java/org/buaa/rag/tool/LlmChat.java`

### 15.1 职责

`LlmChat` 是所有 LLM 调用的统一入口，封装了对 DashScope API 的调用。整个在线链路中共有 **7 处** 使用 LLM：

| 调用场景 | 调用方式 | 模板 |
|----------|----------|------|
| 查询改写与拆分 | 同步 | `query-rewrite-and-split.st` |
| 意图分类 | 同步 | `intent-tree-classifier.st` |
| 歧义确认 | 同步 | 内嵌 prompt |
| CRAG 质量评估 | 同步 | `retrieval-crag.st` |
| 对话摘要压缩 | 异步 | `conversation-summary.st` |
| 会话标题生成 | 异步 | `conversation-title.st` |
| **答案生成** | **流式** | `rag-single-intent.st` 等 |

### 15.2 核心方法

```java
// 同步调用 — 用于改写、意图分类、CRAG 等
String generateCompletion(String systemPrompt, String userPrompt, Integer maxTokens);

// 同步调用（带温度/topP 覆盖）
String generateCompletion(String systemPrompt, String userPrompt, Integer maxTokens,
                          Double temperatureOverride, Double topPOverride);

// 流式调用（简易版）— 用于答案生成
void streamResponse(String userQuery, String referenceContext,
                    List<Map<String,String>> conversationHistory,
                    Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete);

// 流式调用（完整版，带取消支持）
void streamResponseWithHandle(List<Map<String,String>> messages,
                              Double temp, Double topP, Integer maxTokens,
                              Consumer<String> onChunk, Consumer<Throwable> onError,
                              Runnable onComplete, StreamCancellationHandle cancelHandle);
```

### 15.3 调用链

```
LlmChat
    │
    └─► DashscopeClient (HTTP 封装)
            │
            └─► DashScope API (qwen-plus / qwen-max 等)
                    │
                    └─► 流式: SSE 回调逐 token 推送
                        同步: 等待完整响应
```

### 15.4 安全处理

- **输出净化**：去除 LLM 返回中的 Markdown 代码块标记（` ```json ` 等）
- **超时控制**：配置读超时和连接超时
- **异常重试**：网络异常时自动重试（由 DashscopeClient 封装）

---

## 16. 全链路追踪 — RagTrace

### 16.1 架构设计

**文件位置：** `src/main/java/org/buaa/rag/core/online/trace/`

通过 AOP 注解实现自动化的链路追踪，支持前端瀑布图展示各环节耗时。

```
@RagTraceRoot(name = "stream-chat")
    │   标注在 ChatFacade.streamChat() 上 — 创建根 Trace 节点
    │
    ├─► @RagTraceNode(name = "intent-resolve")
    │       IntentResolutionService.resolve() — 记录意图识别耗时
    │
    ├─► @RagTraceNode(name = "route-execution")
    │       RouteExecutionCoordinator.execute() — 记录路由执行耗时
    │
    ├─► @RagTraceNode(name = "sub-query-retrieval")
    │       SubQueryRetrievalService.retrieveForSubQuery() — 记录子查询检索耗时
    │
    ├─► @RagTraceNode(name = "multi-channel-retrieval")
    │       MultiChannelRetrievalEngine.retrieve() — 记录多通道检索耗时
    │
    ├─► @RagTraceNode(name = "crag-quality-evaluate")
    │       RetrievalPostProcessorService.evaluate() — 记录 CRAG 评估耗时
    │
    ├─► @RagTraceNode(name = "llm-stream-chat")
    │       LlmChat.streamResponseWithHandle() — 记录 LLM 流式生成耗时
    │
    └─► 各子节点持久化到 counselor_trace_record / counselor_trace_step 表
```

### 16.2 核心组件

| 组件 | 说明 |
|------|------|
| `@RagTraceRoot` | 标记链路根节点的注解 |
| `@RagTraceNode` | 标记链路子节点的注解 |
| `RagTraceContext` | 基于 ThreadLocal 的上下文，支持跨线程快照传播 |
| `RagTraceAspect` | AOP 切面，自动记录进入/退出时间、状态、异常 |

### 16.3 跨线程传播

对于异步执行（如多意图并行检索），通过 `capture()` / `restore()` 实现上下文传播：

```java
RagTraceContext.Snapshot snapshot = RagTraceContext.capture();
CompletableFuture.runAsync(() -> {
    RagTraceContext.restore(snapshot);
    // 异步任务中的 @RagTraceNode 能正确关联到父节点
});
```

---

## 17. SSE 流式推送机制

### 17.1 推送架构

```
LlmChat (逐 token 回调)
    │
    ▼
StreamChatCallback (回调接口)
    │
    ▼
SseStreamChatEventHandler (SSE 事件封装)
    │
    ├─► event: "meta"     → data: {"messageId": 123, "taskId": "xxx"}
    ├─► event: "message"  → data: {"type": "response", "delta": "你好"}
    ├─► event: "sources"  → data: [{来源文档列表}]
    ├─► event: "finish"   → data: {"title": "会话标题", "messageId": 123}
    ├─► event: "done"     → data: [DONE]
    └─► event: "error"    → data: {"message": "..."}
    │
    ▼
SseEmitter (Spring SSE)
    │
    ▼
HTTP Response (text/event-stream)
```

### 17.2 SSE 事件类型

| 事件类型 | 说明 | 数据结构 |
|----------|------|----------|
| `meta` | 会话元信息（首个事件） | `{"messageId": ..., "taskId": "..."}` |
| `message` | 增量内容 token | `{"type": "response", "delta": "..."}` |
| `sources` | 引用来源列表 | `[{RetrievalMatch 对象}]` |
| `finish` | 生成完成（含标题） | `{"title": "...", "messageId": ...}` |
| `done` | 流式结束标记 | `[DONE]` |
| `error` | 错误信息 | `{"message": "..."}` |

### 17.3 StreamChatCallback 接口

```java
public interface StreamChatCallback {
    void onMeta(Long messageId, String taskId);       // 推送元信息
    void onContent(String chunk);                      // 推送增量内容
    void onSources(List<RetrievalMatch> sources);     // 推送引用来源
    void onFinish(String title, Long messageId);       // 推送完成事件
    void onComplete();                                 // 流式结束
    void onError(Throwable cause);                     // 错误处理
    boolean isCancelled();                             // 检查是否已取消
}
```

### 17.4 取消机制

```
前端关闭连接 / 用户点击停止
    │
    ▼
SseEmitter.onCompletion / onTimeout
    │
    ▼
StreamTaskManager.cancel(taskId) → SseStreamChatEventHandler.markCancelled()
    │
    ▼
LlmChat 中检测 isCancelled() → 停止流式生成
```

---

## 18. 端到端数据流图

以一次典型的 RAG 问答为例，完整数据流如下：

```
用户: "学分绩点怎么算，另外怎么选课？"
    │
    ▼
[1] ConversationController.handleChatStream()
    │   创建 SseEmitter, 提交异步任务
    ▼
[2] ChatFacade.streamChat()                              ← @RagTraceRoot
    │
    ▼
[3] StreamChatPipeline.execute()
    │
    ├─► [Stage 1] prepareSession
    │   ├─ 创建/获取会话 session_123
    │   ├─ 持久化用户消息 msg_456
    │   ├─ 异步生成标题 "学分与选课咨询"
    │   └─ 创建助手占位消息 msg_457
    │
    ├─► [Stage 2] loadConversationHistory
    │   ├─ [并行A] 加载最近 10 条消息
    │   └─ [并行B] 加载对话摘要
    │
    ├─► [Stage 3] rewriteQuery                           ← @RagTraceNode
    │   ├─ 同义词归一化: "绩点" → "学分绩点"
    │   └─ LLM 改写+拆分:
    │       ├─ rewritten: "学分绩点的计算方法？如何在教务系统中选课？"
    │       ├─ subQuery[0]: "学分绩点的计算方法"
    │       └─ subQuery[1]: "如何在教务系统中选课"
    │
    ├─► [Stage 4] resolveIntents                         ← @RagTraceNode
    │   ├─ [并行] IntentRouter for subQuery[0]:
    │   │   ├─ LLM 意图分类 → "kb_academic" (confidence=0.95)
    │   │   └─ 决策: ROUTE_RAG, knowledgeBaseId=1 (academic_kb)
    │   └─ [并行] IntentRouter for subQuery[1]:
    │       ├─ LLM 意图分类 → "kb_academic" (confidence=0.91)
    │       └─ 决策: ROUTE_RAG, knowledgeBaseId=1 (academic_kb)
    │
    ├─► buildRoutingPlan
    │   └─ 2个子查询, 不同意图 → 多意图路径
    │
    └─► [Stage 5] RouteExecutionCoordinator.execute()    ← @RagTraceNode
        │
        └─► executeMultiIntentRoute
            │
            ├─► [并行任务1] subQuery[0]: "学分绩点的计算方法"
            │   │
            │   ├─ MultiChannelRetrievalEngine.retrieve()
            │   │   ├─ IntentDirectedSearchChannel
            │   │   │   └─ SmartRetrieverService.retrieveScoped(knowledgeBaseId=1)
            │   │   │       ├─ Milvus 向量检索 → 8 条候选
            │   │   │       ├─ ES 文本检索 → 5 条候选
            │   │   │       └─ RRF 融合 → 10 条
            │   │   └─ VectorGlobalSearchChannel
            │   │       └─ SmartRetrieverService.retrieveVectorOnly() → 5 条
            │   │
            │   ├─ 后处理链
            │   │   ├─ DeduplicationPostProcessor → 12 条 (去重)
            │   │   ├─ RerankPostProcessor
            │   │   │   └─ DashScopeRerankClient → 按相关性重排
            │   │   ├─ ScoreFilterPostProcessor → 过滤低分 (≥0.4)
            │   │   └─ TopKLimitPostProcessor → 5 条 (截断)
            │   │
            │   └─ CRAG 评估 → ANSWER (质量充分)
            │
            ├─► [并行任务2] subQuery[1]: "如何在教务系统中选课"
            │   │
            │   ├─ MultiChannelRetrievalEngine.retrieve()
            │   │   └─ (同上检索流程, 在 academic_kb 知识库对应集合)
            │   ├─ 后处理链 → 5 条精排结果
            │   └─ CRAG 评估 → ANSWER
            │
            ├─► 收集两个子查询的检索结果
            │
            └─► RagPromptService.generateMultiIntentAnswer()
                │
                ├─ 组装 Prompt:
                │   ├─ System: 角色定义 + 对话摘要
                │   ├─ Context: 两组检索结果
                │   ├─ History: 近期对话
                │   └─ User: 综合提问
                │
                └─ LlmChat.streamResponseWithHandle()
                    │
                    ├─ meta: {"messageId":457, "taskId":"xxx"} → SSE push
                    ├─ message: {"type":"response","delta":"学"} → SSE push
                    ├─ message: {"type":"response","delta":"分"} → SSE push
                    ├─ ...
                    ├─ message: (完整回答约 500 tokens)
                    ├─ sources: [{来源文档}] → SSE push
                    ├─ finish: {"title":"学分与选课咨询","messageId":457} → SSE push
                    └─ done: [DONE] → SSE push

    [最终] 回填助手消息 msg_457 的内容到数据库
```

---

## 19. 配置项速查

以下为在线链路相关的核心配置项（`application.yml`）：

### 19.1 查询预处理

```yaml
rag:
  query-preprocess:
    enabled: true                    # 是否启用查询预处理
    max-sub-questions: 6             # 最大子查询拆分数
```

### 19.2 意图路由与引导

```yaml
rag:
  intent-routing: {}                 # 意图路由（意图树从数据库加载）

  intent-guidance:                   # 意图引导配置
    enabled: true                    # 是否启用意图引导
    hit-threshold: 0.58              # 命中阈值
    ambiguity-ratio: 0.88            # 歧义置信度比率
    max-candidates: 4                # 最大候选数
    max-options: 3                   # 最大引导选项数
    llm-min-score: 0.45              # LLM 最低置信分

  intent-resolution:                 # 意图解析配置
    per-query-max-candidates: 3      # 每个查询最大候选数
    max-total-candidates: 6          # 总最大候选数
    min-score: 0.5                   # 最低分数
```

### 19.3 检索

```yaml
rag:
  retrieval:
    default-top-k: 5                # 默认 Top-K 数量
    max-top-k: 10                   # 最大 Top-K 数量
    min-acceptable-score: 0.4        # 低分过滤阈值（ScoreFilterPostProcessor）

  search:                           # 搜索通道配置
    channels:
      intent-directed:              # 意图定向检索通道
        enabled: true
        min-intent-score: 0.55       # 最低意图分数
        top-k-multiplier: 2          # Top-K 倍数
      vector-global:                # 向量全局检索通道
        enabled: true
        confidence-threshold: 0.65   # 置信度阈值
        top-k-multiplier: 2
        default-confidence: 0.7
    post-processor:
      deduplicate: true              # 是否启用去重
      rerank: true                   # 是否启用 Rerank

  fusion:                           # RRF 融合配置
    enabled: true
    rrf-k: 60                       # RRF 公式中的常数 k
```

### 19.4 Rerank 精排

```yaml
rag:
  rerank:
    enabled: true                   # 是否启用精排
    dashscope:                       # DashScope Rerank 配置
      model: gte-rerank             # 模型名称
      base-url: https://dashscope.aliyuncs.com
      timeout-seconds: 15           # 超时时间
```

### 19.5 CRAG

```yaml
rag:
  crag:
    enabled: true                   # 是否启用 CRAG 质量评估
    use-llm: true                   # 是否使用 LLM 评估
    min-score: 0.2                  # CRAG 最低分数
    review-top-k: 3                 # 评审时取前 K 条
    fallback-multiplier: 2          # 降级检索的 Top-K 倍数
    ambiguity-words:                # 歧义关键词列表
      - "什么"
      - "怎么"
      # ...
    ambiguity-min-length: 6         # 歧义检测最小查询长度
```

### 19.6 对话记忆

```yaml
rag:
  memory:
    history-keep-turns: 4           # 近期保留轮数（每轮含 user + assistant 2 条）
    summary-enabled: true           # 是否启用摘要压缩
    summary-start-turns: 8          # 触发摘要的对话轮数
    summary-max-chars: 320          # 摘要最大字符数
    summary-max-tokens: 180         # 摘要最大 Token 数
    min-delta-messages: 4           # 增量摘要的最小新消息数
    default-max-history: 60         # 默认最大历史消息加载数
```

### 19.7 LLM

LLM 配置分布在 `spring.ai.dashscope` 和 `ai.generation` 两个位置：

```yaml
spring:
  ai:
    model:
      chat: dashscope               # 聊天模型提供方
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}  # API Key（建议从环境变量读取）
      chat:
        options:
          model: qwen-plus           # 默认聊天模型
          temperature: 0.3
          top-p: 0.9
          max-token: 2000
      embedding:
        options:
          model: text-embedding-v4   # 向量编码模型

ai:
  generation:
    temperature: 0.3                # 生成温度
    top-p: 0.9
    max-tokens: 2048
```

### 19.8 语义缓存

```yaml
rag:
  semantic-cache:
    enabled: true                   # 是否启用语义缓存
    min-similarity: 0.92            # 缓存命中的最小相似度
    max-entries: 300                # 最大缓存条目数
    ttl-minutes: 120                # 缓存过期时间（分钟）
```

### 19.9 Prompt 配置

```yaml
rag:
  prompt:
    max-reference-length: 300       # 单个引用片段最大长度
    max-source-reference-count: 5   # 最大引用来源数
    temperature-kb: 0.0             # 知识库回答的温度
    temperature-tool: 0.3           # 工具调用的温度
```

### 19.10 Embedding 配置

```yaml
rag:
  embedding:
    batch-size: 10                  # 批量编码大小
    concurrency: 4                  # 并发编码数
    max-retries: 2                  # 最大重试次数
    retry-backoff-ms: 400           # 重试退避时间（ms）
```

### 19.11 全链路追踪

```yaml
rag:
  trace:
    enabled: true                   # 是否启用链路追踪
    max-error-length: 800           # 错误信息最大长度
```

### 19.12 反馈

```yaml
rag:
  feedback:
    enabled: true                   # 是否启用反馈
    max-boost: 0.15                 # 最大反馈增益
```

---

## 附录：Prompt 模板清单

| 模板文件 | 用途 | 调用位置 |
|----------|------|----------|
| `system-rules.st` | 系统角色定义与规则 | RagPromptService |
| `query-rewrite-and-split.st` | 查询改写与拆分 | QueryRewriteAndSplitService |
| `intent-tree-classifier.st` | 意图树 LLM 分类 | IntentRouterService |
| `rag-single-intent.st` | 单意图 RAG 回答 | RagPromptService |
| `rag-multi-intent.st` | 多意图子查询回答 | RagPromptService |
| `chat-multi-intent-synthesis.st` | 多意图综合生成 | RagPromptService |
| `context-format.st` | 检索上下文格式化 | RagPromptService |
| `conversation-summary.st` | 对话摘要压缩 | ConversationMemoryServiceImpl |
| `conversation-title.st` | 会话标题生成 | ConversationService |
| `guidance-prompt.st` | 意图引导提示 | IntentRouterService |
| `retrieval-crag.st` | CRAG 质量评估 | RetrievalPostProcessorService |
| `retrieval-clarify.st` | 检索澄清引导 | RetrievalPostProcessorService |
| `tool-agent.st` | 工具调用决策 | ToolService |
| `answer-validator.st` | 答案验证 | RagPromptService |
