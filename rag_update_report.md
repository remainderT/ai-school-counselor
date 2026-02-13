## RAG 增量功能说明（init -> HEAD，后端）

> 说明：本文面向你当前代码仓库，聚焦 **RAG 检索与对话链路** 的扩展能力说明。  
> init 阶段你已经完成了“召回 + 重排”的基本框架，本文件在此基础上补充当前演进后的全链路细节。

---

## 1. 总览：从“召回 + 重排”到“多路检索 + 质量控制 + 反馈闭环”

当前系统在 init 的基础上，新增了以下关键能力：

1. **多路检索与融合**  
   - 查询改写（Rewrite）  
   - HyDE（生成“假想答案”用于向量检索）  
   - RRF 融合（多路结果合并重排）

2. **质量控制与兜底（CRAG）**  
   - 检索质量评估  
   - 澄清问题（CLARIFY）  
   - 兜底检索（REFINE/NO_ANSWER）

3. **LLM 重排器（LLM Reranker）**  
   - 对候选片段进行语义相关度精排

4. **检索路由与元数据过滤**  
   - department / docType / policyYear / tags

5. **反馈闭环**  
   - 基于用户评分对文档打分提升检索排序

---

## 2. 检索链路与机制说明

### 2.1 混合检索（召回 + 关键词过滤 + BM25 重排）

**目标**：在“向量召回求全”的基础上引入“关键词匹配 + BM25 精排”求准。  

核心流程（`SmartRetrieverService`）：

1. **向量召回**：  
   - 将查询向量化  
   - kNN 召回大量候选（topK × 30/50）  

2. **文本匹配过滤**：  
   - 使用 match query  
   - 对短查询采用 `OR`，长查询采用 `AND`

3. **BM25 Rescore**：  
   - 对召回窗口做二次 BM25 重排  
   - 最终得分 = 向量得分 × 0.2 + BM25 × 1.0

兜底：  
- 若向量生成失败/索引不存在 → 退化为文本检索  

---

### 2.2 查询改写（Query Rewrite）

**作用**：为一个问题生成多条语义等价或补充语义的查询，提高召回覆盖率。  
**实现**：`QueryRefiner`  
**配置**：`rag.rewrite.enabled / variants / prompt`

---

### 2.3 HyDE（Hypothetical Document Embeddings）

**HyDE 是什么？**  
HyDE（Hypothetical Document Embeddings）是一个“先生成假想答案再检索”的方法：

1. LLM 先根据用户问题生成一个“可能的理想答案”（但不要求真实准确）。  
2. 把这个“假想答案”作为文本输入向量检索。  
3. 通过更具体的语义描述提升召回质量。

**优势**：  
- 对抽象问题、长问题更有效  
- 能扩展语义覆盖面

**实现位置**：  
`QueryRefiner#generateHydeAnswer` + `SmartRetrieverService.retrieveVectorOnly`

---

### 2.4 RRF（Reciprocal Rank Fusion）

**RRF 是什么？**  
RRF 是一种多路检索融合方法，核心思想：  
> 对不同检索结果的排名做加权融合，不直接比较原始分数，而是按“排名位置”累积分数。

公式示意：  
```
score = Σ 1 / (k + rank_i)
```
其中 `rank_i` 表示该结果在第 i 个检索列表中的排名，`k` 为平滑参数。

**意义**：  
- 多路检索（原查询 + 改写 + HyDE）不会互相覆盖  
- 能避免单一路径失效导致的漏召回  

**实现位置**：  
- `ConversationManager#fuseByRrf`  
- `EvalService#fuseByRrf`

---

### 2.5 LLM Reranker（大模型重排器）

**LLM Reranker 是什么？**  
- 先对候选结果做基础排序  
- 取前 N 个候选  
- 调用 LLM 对每个候选片段打相关度分（0~1）  
- 根据分数重新排序  

**作用**：  
- 弥补传统 BM25/向量检索的“语义偏差”  
- 对复杂问题更稳健  

**实现位置**：  
`RerankerService`  

**配置**：  
`rag.rerank.enabled / max-candidates / snippet-length / prompt`

---

### 2.6 CRAG（检索质量评估 + 兜底策略）

**CRAG 是什么？**  
CRAG（Corrective RAG）强调“检索质量评估”，在检索结果不足时触发兜底策略，避免直接输出幻觉答案。

**你当前实现的机制**：  
1. 评估结果质量  
2. 若检索结果不足 → 触发：  
   - `CLARIFY`：生成澄清问题  
   - `REFINE`：触发兜底检索  
   - `NO_ANSWER`：直接返回“无答案”  

**实现位置**：  
`CragService` + `CragDecision`

---

## 3. ChatController 的完整链路（当前版本）

以下以 `POST /api/chat` 为例描述当前链路（`ChatController -> ChatService -> ConversationManager`）：

### 3.1 请求入口
1. `ChatController.handleChatRequest()`  
2. 进入 `ChatService.handleChatRequest()`

---

### 3.2 对话处理主流程（ConversationManager）

1. **会话管理**  
   - 根据 `userId` 获取或创建 `sessionId`  
   - 加载历史对话（最多 20 条）

2. **检索策略确定**
   - `QueryRoutingService` 抽取元数据（学院/类型/年份/标签）
   - `QueryRefiner` 生成重写查询 + HyDE

3. **多路检索**
   - 混合查询检索  
   - 重写查询检索  
   - HyDE 向量检索  

4. **RRF 融合**
   - 多路结果合并为统一排序

5. **LLM Reranker**
   - 对候选前 N 进行重排  

6. **CRAG 质量判断**
   - 结果过弱 → REFINE/CLARIFY/NO_ANSWER  
   - REFINE 会触发文本-only 的兜底检索  

7. **构造参考上下文**
   - 将检索片段拼成上下文  
   - 附带来源文件名  

8. **调用 LLM 生成回答**
   - `LlmChatTool.streamResponse()`  
   - 最终输出 AI 响应  

9. **持久化**
   - 保存用户消息与 AI 回复  
   - 保存来源片段  
   - 留作反馈闭环使用  

---