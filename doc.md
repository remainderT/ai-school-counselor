# AI 辅导员 Agentic RAG 统一需求说明

## 1. 目标与范围
本项目目标：基于 Java + Spring Boot + Spring AI Alibaba，实现“AI 辅导员”后端，支持以下能力：
- 文档入库：文件上传到 RustFS，解析切块并向量化，写入 Elasticsearch。
- 意图路由：基于规则 + 语义路由 + LLM 分类 + 意图树进行轻量化分流。
- Agentic RAG：按意图动态选择“RAG 检索回答”或“工具调用（Function Calling）”。
- 可解释输出：回答附引用来源与路由结果，支持低置信度澄清。

非目标：
- 不做前端复杂改造。
- 不做复杂多 Agent 状态图编排（Graph），优先轻量可落地。

## 2. 技术栈与版本
- Java 17
- Spring Boot 3.4.x
- Spring AI 1.0.1 + Spring AI Alibaba DashScope Starter 1.0.0.2
- Elasticsearch 8.x（向量检索 + BM25 + RRF）
- RustFS 8.xs
- MyBatis-Plus（业务数据持久化）

## 3. 总体调用链路
1. 用户请求进入 `ChatController`。
2. `IntentRouterService.decide()` 输出 `IntentDecision`：
   - `CRISIS`：危机词直接拦截。
   - `ROUTE_TOOL`：调用 `ToolService`。
   - `ROUTE_RAG`：执行检索与回答。
   - `CLARIFY`：返回澄清问题。
3. `ROUTE_RAG` 时执行：
   - Query Rewrite / HyDE / Metadata Filter
   - 向量 + 文本混合检索与重排
   - LLM 结合上下文生成答案并附来源

## 4. 意图树设计
在传统 RAG 中，推理流程固定为 `Query -> Retrieve -> Prompt -> LLM Generation`。引入意图树后，核心收益：
- 剪枝搜索空间：先定位领域，避免全量向量库检索。
- 动态提示词：不同节点挂载不同 prompt 模板。
- 确定性路由：硬性需求可直达工具，减少 token 与延迟。

### 4.1 节点数据结构
```java
public class IntentNode {
    private String nodeId;
    private String nodeName;
    private String parentId;
    private NodeType type; // GROUP | RAG_QA | API_ACTION | CHITCHAT
    private String description;
    private String promptTemplate;
    private List<String> keywords;
    private String knowledgeBaseId;
    private String actionService;
}
```

### 4.2 一级领域与典型子类目
1. 教务教学
   - 学籍管理（转专业、休复学、注册注销）
   - 课程与选课（选课规则、重修补考、学分认定）
   - 考试与成绩（GPA、四六级、考场纪律）
2. 学生事务与奖助
   - 奖学金与荣誉（国家奖学金、校奖、评优）
   - 资助与贷款（助学贷款、勤工助学、困难认定）
   - 日常行政（请假销假、综测、证明打印）
3. 校园生活服务
   - 宿舍管理（门禁归寝、报修水电、调宿）
   - 校园卡与网络（一卡通、校园网）
   - 医疗与后勤（校医院、校车）
4. 就业与职业发展
   - 就业手续（三方、档案、报到证）
   - 升学指导（保研、考研、调剂）
5. 心理与安全
   - 心理咨询（预约流程、援助热线）
   - 校园安全（防诈骗、紧急求助）

## 5. 路由算法（核心）
采用五段式轻量路由，避免所有请求进入重推理。

### Stage 0：危机规则拦截
- 命中高危词（如自伤倾向）直接返回 `CRISIS`。
- 不进入普通 RAG，优先给出紧急联系方式。

### Stage 1：关键词工具直达
- 请假/报修/成绩等硬需求命中关键词后直接 `ROUTE_TOOL`。
- 目标：缩短时延、减少 token 开销。

### Stage 2：意图树 Beam Search
- 从根节点开始逐层计算相似度，Top-2 下钻。
- 相似度建议：`keyword_score + embedding_similarity`。
- 若 Top1 与 Top2 分差小于阈值（建议 0.1），返回 `CLARIFY`。

### Stage 3：语义路由（Intent Seeds + ES 向量索引）
- 索引：`intent_patterns`
- 数据：`intent-seeds.json` 样本问句
- 命中阈值建议：
  - `>= 0.90`：直接采纳
  - `0.70~0.90`：进入 LLM 复核
  - `< 0.70`：视为未命中

### Stage 4：LLM 结构化分类兜底
通过 Spring AI Alibaba `ChatClient` 输出结构化 JSON：

```json
{
  "level1": "学业指导|办事指南|心理与生活|日常闲聊|其他",
  "level2": "具体意图",
  "confidence": 0.0,
  "toolName": "leave|repair|score|none",
  "clarify": "可选澄清问题"
}
```

决策规则：
- `toolName != none` -> `ROUTE_TOOL`
- `confidence >= 0.5` -> `ROUTE_RAG`
- 否则 -> `CLARIFY`

## 6. Agentic RAG 编排
### 6.1 ROUTE_RAG
1. Query 改写（可选）
2. HyDE 伪答案生成（可选）
3. Metadata 过滤解析（学院/文档类型/年份/tags）
4. 混合检索（向量 + BM25 + RRF）
5. 答案生成 + 引用来源
6. CRAG 评估，不足则触发 refine/clarify

### 6.2 ROUTE_TOOL
- 使用 `@Tool` 绑定能力：
  - `queryGrade(studentId)`
  - `createLeaveDraft(userId, start, end, reason)`
  - `createRepairTicket(userId, dorm, issue)`
- LLM 只负责“何时调用工具 + 结果解释”，业务约束由后端校验。
- 工具调用走白名单，不允许模型自由执行未知函数。

### 6.3 多意图与多跳推理
当问题涉及多个叶子节点（如“挂科会影响国奖吗”）：
1. `QueryDecomposer` 拆子问题
2. 每个子问题分别路由执行（RAG 或 Tool）
3. 用综合 Prompt 合并中间结论
4. 输出最终结论与证据链

## 7. 交互式澄清机制
当系统无法可靠区分子意图（如“奖学金怎么申请”）时：
1. 暂停 RAG 主流程
2. 返回澄清问题（如国奖/校奖/社会奖学金）
3. 用户补充后收敛到单节点检索
4. 输出精准回答

## 8. Spring AI Alibaba 落地要求
### 8.1 LLM 调用
- 统一通过 `ChatClient` 执行聊天、分类、重排、CRAG 评估。
- 为“意图分类、改写、澄清、答案生成”定义独立 system prompt。
- SSE 接口保持 `/api/rag/chat/stream` 协议不变。

### 8.2 Embedding 调用
- 统一通过 `EmbeddingModel` 生成向量。
- 批量向量化保留批次机制（与 `batch-size` 对齐）。
- 向量失败时回退文本检索。

### 8.3 Function Calling
- 使用 `@Tool` 注册工具能力。
- Tool 返回结构化结果（JSON/DTO），再由 LLM 汇总成自然语言答复。

## 9. 模块划分（与项目结构对齐）
- `config/`：AI、ES、RustFS、HTTP 配置
- `service/impl/DocumentServiceImpl`：文档摄取（上传、切块、入索引）
- `service/impl/SmartRetrieverServiceImpl`：混合检索
- `service/impl/IntentTreeServiceImpl`：加载 `intent-tree.json`
- `service/impl/IntentPatternServiceImpl`：意图样本向量路由（`intent_patterns`）
- `service/impl/IntentRouterServiceImpl`：混合路由决策
- `service/impl/ToolServiceImpl`：工具执行（请假/报修/成绩）
- `service/impl/ChatServiceImpl`：主流程编排（路由、检索、回答、反馈）

## 10. 实施优先级
1. 先补真实向量相似度，替换意图树占位实现。
2. 再将分类与生成统一到 `ChatClient`。
3. 最后接入 `@Tool` 的成绩/请假/报修三类能力，并补全集成测试。

## 11. 里程碑与验收
### M1：基础可用
- 文档上传 -> 切块 -> 向量入 ES 成功。
- RAG 问答可返回来源。

### M2：意图树 + 路由
- `intent-tree.json` 生效，支持澄清分支。
- 规则/语义/LLM 三层路由均可命中。

### M3：Agentic 工具调用
- 成绩/请假/报修工具通过 Function Calling 调用。
- 工具链路错误可兜底提示。

### M4：优化与论文指标
- 平均响应时延、首 token 时延、命中率、澄清率、幻觉率形成可复现实验数据。

## 12. 最小可测用例
- 用例1：`怎么请假` -> `ROUTE_TOOL(leave)`
- 用例2：`计算机学院保研条件` -> `ROUTE_RAG(保研节点)`
- 用例3：`我不想活了` -> `CRISIS`
- 用例4：`奖学金怎么申请`（模糊） -> `CLARIFY`
- 用例5：`我挂了一门选修，会影响国奖吗` -> 多跳推理成功

## 13. 开发执行清单（建议）
1. 实现 `AiClientConfiguration + CounselorTools + IntentClassifyResult`。
2. 改造 `IntentRouterServiceImpl` 输出标准 `IntentDecision`。
3. 改造 `LlmPort/VectorEncoding` 到 Spring AI 实现，保持接口不变。
4. 补充集成测试：路由、工具调用、RAG 回答、澄清流程。
