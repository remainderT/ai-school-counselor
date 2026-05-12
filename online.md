# 在线问答链路与 CRAG 所在步骤

> 本文按当前源码梳理：一条用户问题从前端进入，到最终流式回答、保存消息、返回来源的完整流程。重点结论是：**CRAG 不在问题改写、意图识别或检索通道内部，而是在“检索完成、答案生成之前”的质量评估步骤。**

## 1. 总览

当前在线问答入口是：

- `GET /api/rag/conversations/stream?message=...`
- Controller: `src/main/java/org/buaa/rag/controller/ConversationController.java`
- 应用服务: `src/main/java/org/buaa/rag/core/online/chat/ChatFacade.java`
- 主编排器: `src/main/java/org/buaa/rag/core/online/chat/StreamChatPipeline.java`

一条用户问题的主流程如下：

```text
用户提问
  ↓
ConversationController.handleChatStream()
  ↓
ChatFacade.handleChatStream()
  ↓
ChatFacade.streamChat()
  ↓
StreamChatPipeline.execute()
  ↓
1. 会话准备与用户消息入库
  ↓
2. 加载对话历史
  ↓
3. 查询改写与拆分
  ↓
4. 子问题意图识别
  ↓
5. 构建路由计划
  ↓
6. 短路判断：澄清 / 纯闲聊
  ↓
7. RouteExecutionCoordinator 执行路由
  ↓
8. RAG 路由：检索
  ↓
9. CRAG 质量评估  ← 当前 CRAG 所在位置
  ↓
10. 根据 CRAG 决策：回答 / 兜底检索 / 澄清 / 无答案
  ↓
11. LLM 生成最终回答并 SSE 流式推送
  ↓
12. 保存助手消息、来源、结束事件
```

## 2. CRAG 在哪一步

CRAG 的实现类是：

- `src/main/java/org/buaa/rag/core/online/retrieval/postprocessor/RetrievalPostProcessorService.java`
- 核心方法：`evaluate(String query, List<RetrievalMatch> matches)`
- Trace 节点：`@RagTraceNode(name = "crag-quality-evaluate", type = "CRAG_EVAL")`
- Prompt：`src/main/resources/prompts/retrieval-crag.st`

它在两种路径中被调用。

### 2.1 单意图 RAG 路径

位置：

- `RouteExecutionCoordinator.executeSingleIntentRoute()`

调用顺序：

```text
determineTopK(query)
  ↓
subQueryRetrievalService.retrieveByStrategy(...)
  ↓
postProcessorService.evaluate(query, retrievalResults)  ← CRAG
  ↓
根据 CragDecision 决定后续动作
```

也就是说，单意图时 CRAG 位于：

```text
多通道检索 + 后处理之后
最终答案生成之前
```

### 2.2 多意图 / 多子问题 RAG 路径

位置：

- `RouteExecutionCoordinator.executeMultiIntentRoute()`
- `RouteExecutionCoordinator.executeSubQueryTask()`
- `SubQueryRetrievalService.retrieveForSubQuery()`

调用顺序：

```text
多个子问题并行执行
  ↓
每个 RAG 子问题进入 SubQueryRetrievalService.retrieveForSubQuery()
  ↓
retrieveByStrategy(...)
  ↓
postProcessorService.evaluate(query, results)  ← 每个子问题各自 CRAG
  ↓
汇总子问题结果
  ↓
多意图快速结构化返回或综合生成
```

所以多意图时，CRAG 是**子问题粒度**的质量评估：每个需要 RAG 的子问题检索后，都先过一遍 CRAG，再进入多意图汇总。

## 3. 从开始到结束的详细流程

### 3.1 请求进入

前端通过 SSE 调用：

```text
GET /api/rag/conversations/stream?message=用户问题
```

`ConversationController.handleChatStream()` 调用 `chatService.handleChatStream()`。

`ChatFacade.handleChatStream()` 做这些事：

1. 校验 `message` 是否为空。
2. 创建 `SseEmitter`。
3. 生成 `taskId`。
4. 创建 `SseStreamChatEventHandler`。
5. 绑定 SSE 断开、超时后的取消逻辑。
6. 在线程池 `chatStreamExecutor` 中异步执行 `streamChat()`。

真正的在线问答 Trace 从这里开始：

```java
@RagTraceRoot(name = "stream-chat", taskIdArg = "taskId")
public void streamChat(...)
```

### 3.2 会话准备

位置：

- `StreamChatPipeline.prepareSession()`

做这些事：

1. 获取或创建当前用户会话。
2. 把用户原始问题写入消息表。
3. 同步生成并持久化会话标题。
4. 创建助手消息占位记录。
5. 通过 SSE 发送 `meta`，包含助手消息 ID 和任务 ID。
6. 绑定取消回调，用户取消时把助手消息标记为失败。

### 3.3 加载对话历史

位置：

- `StreamChatPipeline.loadConversationHistory()`
- `ConversationService.loadConversationContext()`

这一步会加载当前会话上下文，后续用于：

- 查询改写时做指代消歧。
- 纯聊天、工具回答、RAG 最终生成时提供上下文。

### 3.4 查询改写与拆分

位置：

- `StreamChatPipeline.rewriteQuery()`
- `QueryRewriteAndSplitService.rewriteWithSplit()`

主要逻辑：

1. 空问题直接 fallback。
2. 如果 `rag.query-preprocess.enabled=false`，透传原问题。
3. 先做词项归一化：`QueryTermMappingService.normalize()`。
4. 如果命中规则快速路径，用分号、换行、“另外”、“还有”等规则拆分。
5. 否则调用 LLM，通过 `query-rewrite-and-split.st` 完成：
   - 改写当前问题。
   - 拆分为多个子问题。
   - 利用最近两轮历史做指代消歧。

产物是 `QueryRewriteResult`：

- `rewrittenQuery`
- `subQuestions`
- `latencyMs`

### 3.5 子问题意图识别

位置：

- `StreamChatPipeline.resolveIntents()`
- `IntentResolutionService.resolve()`
- `IntentRouterService.rankIntentCandidates()`

流程：

1. 使用 `rewriteResult.effectiveSubQuestions()` 得到有效子问题。
2. 每个子问题并行做意图识别。
3. `IntentRouterService` 加载意图树叶子节点。
4. LLM 使用 `intent-tree-classifier.st` 对意图节点打分。
5. 得到候选 `IntentDecision` 列表。
6. 如果没有命中，会追加或使用“综合规章”兜底 RAG 意图。

意图动作主要包括：

- `ROUTE_RAG`：走知识库检索增强问答。
- `ROUTE_TOOL`：走工具 / MCP。
- `ROUTE_CHAT`：纯聊天。
- `CLARIFY`：需要澄清。

### 3.6 构建路由计划

位置：

- `StreamChatPipeline.buildRoutingPlan()`

如果没有任何已解析子问题，则构造默认 HYBRID RAG 意图：

```text
action = ROUTE_RAG
strategy = HYBRID
```

随后进入短路判断。

### 3.7 短路 1：意图澄清

位置：

- `StreamChatPipeline.detectGuidanceQuestion()`

如果主意图是 `CLARIFY`，并且有 `clarifyQuestion`，系统直接通过 SSE 返回澄清问题，然后保存助手消息并结束。

这条路径不进入检索，也不会执行 CRAG。

```text
意图识别 → CLARIFY → 直接返回澄清问题 → 结束
```

### 3.8 短路 2：纯系统聊天

位置：

- `StreamChatPipeline.isAllSystemChat()`
- `StreamChatPipeline.streamSystemDirectResponse()`

如果所有子问题都是 `ROUTE_CHAT`，系统跳过检索，直接调用 LLM 流式生成闲聊或引导回复。

这条路径也不会执行 CRAG。

```text
意图识别 → ROUTE_CHAT → 直接 LLM 生成 → 结束
```

## 4. 路由执行阶段

正常路径进入：

- `RouteExecutionCoordinator.execute()`

这里根据子问题数量分为两条主线：

- 单意图：`executeSingleIntentRoute()`
- 多意图：`executeMultiIntentRoute()`

### 4.1 单意图路由

单意图按动作分支：

```text
CLARIFY
  → 直接返回澄清

ROUTE_TOOL
  → ToolService.execute()
  → RagPromptService.generateSingleIntentToolAnswer()
  → 流式输出

ROUTE_CHAT
  → RagPromptService.generateRagAnswerWithoutReferences()
  → 流式输出

ROUTE_RAG
  → retrieveByStrategy()
  → CRAG evaluate()
  → 生成或兜底
```

单意图 RAG 的完整顺序是：

```text
计算 topK
  ↓
SubQueryRetrievalService.retrieveByStrategy()
  ↓
MultiChannelRetrievalEngine.retrieve()
  ↓
检索通道并行执行
  ↓
检索后处理链
  ↓
RetrievalPostProcessorService.evaluate()  ← CRAG
  ↓
RagPromptService.generateSingleIntentStructuredAnswer()
  ↓
SSE 流式输出
```

### 4.2 多意图路由

如果改写后有多个子问题，则进入多意图并行执行。

位置：

- `RouteExecutionCoordinator.executeMultiIntentRoute()`

流程：

1. 过滤空子问题。
2. 捕获当前 Trace 上下文。
3. 每个子问题用 `CompletableFuture` 并行执行。
4. 每个子问题进入 `executeSubQueryTask()`。
5. 子问题根据主意图选择工具、澄清、聊天或 RAG。
6. 所有子问题完成后汇总结果。
7. 如果没有可用证据，降级为单意图默认 HYBRID RAG。
8. 如果满足快速路径，直接结构化拼接结果。
9. 否则调用 `RagPromptService.generateMultiIntentAnswer()` 综合生成最终回答。

多意图中的 RAG 子问题流程：

```text
executeSubQueryTask()
  ↓
SubQueryRetrievalService.retrieveForSubQuery()
  ↓
retrieveByStrategy()
  ↓
CRAG evaluate()  ← 子问题级 CRAG
  ↓
返回 SubQueryRetrievalResult
```

## 5. 检索阶段

### 5.1 策略选择

位置：

- `SubQueryRetrievalService.retrieveByStrategy()`

根据 `IntentDecision.Strategy` 选择检索方式：

- `PRECISION`：文本精确检索 `smartRetrieverService.retrieveTextOnly()`，然后 rerank。
- `CLARIFY_ONLY`：直接返回空结果。
- `HYBRID`：进入多通道检索 `retrieveWithFusion()`。

### 5.2 多通道检索

位置：

- `MultiChannelRetrievalEngine.retrieve()`
- `MultiChannelRetrievalEngine.dispatch()`

核心步骤：

1. 根据当前请求筛选可用 `SearchChannel`。
2. 按 `dispatchOrder` 排序。
3. 并行执行检索通道。
4. 每个通道有 30 秒超时保护。
5. 扁平合并所有通道结果。
6. 依次执行后处理器。
7. 如果只有意图定向检索且结果偏弱，可补充全局向量检索。

当前检索通道包括：

- `IntentDirectedSearchChannel`：意图定向检索。
- `VectorGlobalSearchChannel`：全局向量检索。

### 5.3 检索后处理链

`MultiChannelRetrievalEngine` 会执行实现了 `SearchResultPostProcessor` 的后处理器。

当前文件包括：

- `DeduplicationPostProcessor.java`
- `RerankPostProcessor.java`
- `ScoreFilterPostProcessor.java`
- `TopKLimitPostProcessor.java`

注意：这些后处理器属于“检索结果排序与过滤”，而 CRAG 是后面单独调用的 `RetrievalPostProcessorService.evaluate()`。

## 6. CRAG 决策逻辑

位置：

- `RetrievalPostProcessorService.evaluate()`

配置：

- `RagProperties.Crag.enabled`
- `RagProperties.Crag.useLlm`
- `RagProperties.Crag.minScore`
- `RagProperties.Crag.reviewTopK`
- `RagProperties.Crag.fallbackMultiplier`
- `RagProperties.Crag.ambiguityWords`
- `RagProperties.Crag.ambiguityMinLength`

当前默认值在 `RagProperties` 中：

```text
enabled = true
useLlm = true
minScore = 0.2
reviewTopK = 3
fallbackMultiplier = 2
ambiguityMinLength = 6
ambiguityWords = ["这个", "那个", "之前", "上面", "怎么弄", "怎么办"]
```

CRAG 返回 `CragDecision`，动作包括：

- `ANSWER`：检索质量可以，继续生成答案。
- `REFINE`：检索质量不足，触发兜底检索。
- `CLARIFY`：问题或证据不足以回答，返回澄清问题。
- `NO_ANSWER`：没有可用资料，返回无结果提示。

### 6.1 无检索结果

如果 `matches` 为空：

```text
matches 为空
  ↓
isLikelyAmbiguous(query)
  ↓
是：CLARIFY，生成澄清问题
否：NO_ANSWER，返回 noResultMessage()
```

### 6.2 有检索结果且需要 LLM 复核

如果开启 `useLlm`，并且 top-1 分数较低：

```text
topScore < minScore * 1.5
  ↓
调用 retrieval-crag.st
  ↓
LLM 输出 JSON
  ↓
解析 action
```

LLM 复核只看前 `reviewTopK` 条候选，每条候选文本截断到 240 字。

### 6.3 不需要 LLM 复核或 LLM 复核失败

如果 top-1 分数低于 `minScore`：

```text
topScore < minScore
  ↓
REFINE
```

否则：

```text
ANSWER
```

## 7. CRAG 后续动作

### 7.1 ANSWER

继续生成最终回答。

单意图：

```text
RagPromptService.generateSingleIntentStructuredAnswer()
```

多意图：

```text
RagPromptService.generateMultiIntentAnswer()
```

或者在满足快速路径时由 `RouteExecutionCoordinator.stitchFastPathResponse()` 直接结构化拼接。

### 7.2 REFINE

触发兜底检索：

- `SubQueryRetrievalService.fallbackRetrieval()`

兜底策略：

1. 读取 `rag.crag.fallbackMultiplier`。
2. `fallbackK = topK * fallbackMultiplier`，但不超过 `rag.retrieval.maxTopK`。
3. 使用 `smartRetrieverService.retrieveTextOnly()` 做文本检索。
4. 再调用 `postProcessorService.rerank()` 重排。

如果兜底结果为空，则返回无结果提示。

### 7.3 CLARIFY

直接返回澄清问题。

单意图路径会直接 SSE 输出 `decision.getMessage()`。

多意图路径会把该子问题标记为 `clarifyTriggered=true`，后续由多意图汇总逻辑处理。

### 7.4 NO_ANSWER

返回 `noResultMessage()`。

默认是：

```text
暂无相关信息
```

如果 `llm.prompt-template.no-result-text` 有配置，则优先使用配置文案。

## 8. 答案生成与流式输出

位置：

- `RagPromptService`
- `LlmChat`
- `SseStreamChatEventHandler`

生成分支：

- 单意图 RAG：`generateSingleIntentStructuredAnswer()`
- 多意图 RAG：`generateMultiIntentAnswer()`
- 工具答案：`generateSingleIntentToolAnswer()`
- 纯聊天：`generateRagAnswerWithoutReferences()`

流式输出逻辑：

1. `RagPromptService` 调用 `LlmChat`。
2. LLM 每产生一个 chunk，就通过 `chunkHandler` 回调。
3. `StreamChatPipeline` 检查 SSE 是否已取消。
4. 未取消则调用 `callback.onContent(chunk)`。
5. 前端持续收到内容片段。

## 9. 收尾

位置：

- `StreamChatPipeline.completeSession()`

做这些事：

1. `conversationService.completeAssistantMessage()` 保存完整助手回答和来源。
2. `callback.onSources(result.sources())` 发送来源列表。
3. `callback.onFinish(title, assistantMessageId)` 发送结束元信息。
4. `callback.onComplete()` 完成 SSE。
5. `ChatFacade.streamChat()` 的 `finally` 中解绑任务。

## 10. 一句话定位

当前系统里，CRAG 是在线链路中的**检索质量控制节点**：

```text
查询改写 → 意图识别 → 路由 → 检索 → 检索后处理 → CRAG → 答案生成 → SSE 返回
```

它的职责不是检索，也不是生成答案，而是在检索和生成之间判断：

- 证据是否足够回答。
- 是否需要兜底重检索。
- 是否应该向用户追问。
- 是否应该直接告知暂无资料。

