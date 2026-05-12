# RAG 消融实验准备文档

## 1. 实验目标

本实验用于验证高校智能辅导员问答系统中，完整 RAG 链路相对多组基线方法的效果提升。实验参考 Ragent 项目中“检索阶段、生成阶段、端到端阶段”的分层评估思路，不只观察最终答案是否正确，也要定位错误来自检索、生成、知识库覆盖还是工具路由。

具体目标如下：

- 验证完整系统在检索召回、排序质量、答案正确率、忠实度、幻觉控制和兜底能力上的综合表现。
- 通过消融对照说明：单纯 BM25、单路向量或普通 Hybrid+RRF，并不能完全替代意图树、多通道调度、重排、CRAG 和 MCP 工具路由。
- 将知识库静态问答与教务 MCP 动态数据问答统一纳入评测，体现系统不是只优化单点，而是把查询改写、意图路由、混合检索、重排、CRAG、工具调用和结构化 Prompt 组合成完整链路。
- 形成可复现实验流程：固定数据集、固定模型、固定评分规则，输出 JSON、CSV 和 Markdown 报告，便于论文结果统计与 Bad Case 分析。

## 2. 分层评估框架

RAG 系统不能只看最终回答是否正确。参考 Ragent 评估方法，本实验将指标分为三层。

### 2.1 检索阶段指标

检索阶段评估回答“正确证据有没有被召回，排得靠不靠前”。

| 指标 | 含义 | 计算方式 | 本实验用途 |
| --- | --- | --- | --- |
| Hit Rate | Top-K 中是否命中任一标准证据 chunk | 命中为 1，未命中为 0，取平均 | 衡量基本召回能力 |
| MRR | 标准证据第一次出现的倒数排名 | 第 1 位为 1，第 2 位为 0.5，未命中为 0 | 衡量排序质量 |
| Recall | 标注相关 chunk 中被召回的比例 | 命中相关 chunk 数 / 标注相关 chunk 数 | 衡量证据覆盖度 |
| Precision | 召回结果中相关 chunk 的比例 | 命中相关 chunk 数 / 返回 chunk 数 | 衡量上下文噪音 |

本实验更关注 Hit Rate、MRR 和 Recall。原因是 RAG 场景中漏掉正确证据通常比召回少量噪音更严重，噪音可以通过 rerank、CRAG 和 Prompt 约束继续处理，但正确 chunk 没召回时生成阶段很难补救。

### 2.2 生成阶段指标

生成阶段评估回答“模型有没有基于证据回答，有没有答非所问或编造”。

| 指标 | 含义 | 评分范围 | 关注问题 |
| --- | --- | --- | --- |
| Faithfulness | 答案是否忠实于检索证据 | 1 到 5 分 | 是否编造证据外信息 |
| Relevance | 答案是否回应用户问题 | 1 到 5 分 | 是否答非所问 |
| Hallucination Rate | 明显幻觉比例 | true/false 汇总 | 系统级幻觉风险 |

其中 Faithfulness 与 Correctness 不完全等价。模型可能没有基于证据回答，但碰巧答对；也可能忠实复述了过时知识库内容，但与现实不符。因此论文中需要同时呈现检索指标、生成指标和端到端指标。

### 2.3 端到端指标

端到端指标评估“最终用户拿到的答案是否可用”。

| 指标 | 含义 | 计算方式 |
| --- | --- | --- |
| Correctness | 回答与标准答案的语义一致程度 | LLM-as-Judge 打 1 到 5 分 |
| Answer Accuracy | 正确率 | Correctness >= 4 的比例 |
| Fallback Success Rate | 无答案题是否合理兜底 | 对 expectedNoAnswer=true 的样本统计 |

最终论文中建议重点报告 Answer Accuracy、平均 Correctness、Hit Rate、MRR、Recall、Faithfulness 和 Hallucination Rate。

## 3. 对照组设计

本实验设置三组 baseline 和一组完整系统，共四组主实验。

| 组别 | 名称 | 检索方式 | 分块方式 | 是否有意图树 | 是否有 CRAG | 是否有交叉编码重排 | 是否有 MCP | 作用 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Baseline-A | BM25 Keyword RAG | Elasticsearch BM25 文本检索 | 线上原始 chunk | 否 | 否 | 否 | 否 | 验证纯关键词检索下限 |
| Baseline-B | Naive Vector RAG | 固定 512 字符重分块 + 单路向量检索 | 固定长度重分块 | 否 | 否 | 否 | 否 | 验证朴素向量 RAG |
| Baseline-C | Hybrid+RRF RAG | 全局 BM25 + 向量检索 + RRF 融合 | 线上原始 chunk | 否 | 否 | 否 | 否 | 验证普通混合召回是否足够 |
| System | Full System | 查询改写 + 意图树 + 多通道检索 + rerank + CRAG + MCP | 结构感知 chunk | 是 | 是 | 是 | 是 | 本文方法 |

Baseline-C 是新增消融组，严格去掉意图树、CRAG 和交叉编码重排，只保留普通混合检索与 RRF 融合。它用于证明“普通 Hybrid+RRF”并不足以覆盖完整系统的优势。


## 4. 模型与服务配置

本实验统一使用 DashScope 兼容接口。

| 用途 | 模型/服务 | 配置位置 | 说明 |
| --- | --- | --- | --- |
| 答案生成 | `qwen-plus` | `src/main/resources/application.yml` | System 与 Java baseline 的生成模型 |
| LLM-as-Judge | `qwen-plus` | `RagExperimentManualTest` 调用 `LlmChat` | 对 correctness、faithfulness、relevance 等打分 |
| Query rewrite / intent / CRAG | `qwen-plus` | 系统在线链路 | 完整系统内部使用 |
| Embedding | `text-embedding-v4` | `spring.ai.dashscope.embedding.options.model` | 向量检索与 Baseline-B embedding |
| Rerank | `gte-rerank` | `rag.rerank.dashscope.model` | System 交叉编码重排 |

为了公平，Java 侧 Baseline-A/B/C 和 System 的最终生成尽量使用相同生成模型；区别主要来自检索、路由、重排、CRAG 与 MCP 能力。

## 4.1 变量控制与公平性约束

为避免消融实验变成“不同系统随意堆配置”的比较，本实验固定以下条件：

- 同一份 `manual_eval_dataset.json` 输入所有组别。
- Java 侧 Baseline-A/B/C 与 System 使用同一生成模型、同一 Judge 模型和同一评分规则。
- Baseline-A/B/C 不接入 MCP，因为它们代表静态知识库 RAG；MCP 能力只属于完整系统。
- Baseline-C 只做普通混合检索与 RRF 融合，不使用意图树、CRAG、交叉编码 rerank，也不使用工具路由。
- System 不额外新增轻量关键词/片段扫描通道，优化集中在已有链路参数与模块协同上，例如召回候选数、意图路由、rerank、CRAG 阈值、Prompt 证据约束和 MCP 参数提取。

## 5. 数据集准备

### 5.1 语料来源

语料直接来自当前线上 MySQL 知识库，并复用系统当前导入后的 chunk 数据。

当前快照规模：

- 知识库：9 个。
- 文档：415 篇。
- chunk：1313 条。
- 知识库正样本覆盖文档：150 篇。

### 5.2 评测集规模

当前正式评测集为 500 条，文件位置：

- `evaluation-output/rag-experiment/manual_eval_dataset.json`
- 同步副本：`evaluation-output/rag-experiment/auto_eval_dataset.json`

样本构成：

| 类型 | 数量 | 说明 |
| --- | ---: | --- |
| 知识库正样本 | 420 | 从真实 chunk 生成，有标准答案和 evidence chunk 标注 |
| MCP 直接工具样本 | 20 | 直接调用成绩、考试、课表等 MCP 工具即可回答 |
| MCP 数据推理样本 | 20 | 需要基于 MCP 返回数据做统计、比较、筛选或判断 |
| 无答案样本 | 40 | 当前知识库和工具都不应回答，用于测试兜底 |
| 总计 | 500 | 论文正式实验集 |

### 5.3 知识库正样本构建

知识库正样本按以下流程生成：

1. 从 MySQL 读取知识库、文档和 chunk。
2. 过滤低质量 chunk：过短、过长、噪声过多、有效字符比例过低的片段不出题。
3. 按知识库和文档分层抽样，优先保证更多文档被覆盖。
4. 对每个高质量 chunk 调用 LLM 生成多个自然语言问题、标准答案、问题类型、难度和关键词。
5. 去重相同或高度相似 query。
6. 保留 `relevantChunks` 字段，记录标准证据的 `documentId` 与 `fragmentIndex`，用于计算 Hit Rate、MRR、Recall 和 Precision。

正样本问题类型覆盖 policy、procedure、eligibility、material、time、contact、course、statistic 等高校事务常见问题。

### 5.4 MCP 样本构建

MCP 样本分两类。

直接工具题：

- 直接问某学期成绩。
- 直接问某学期考试安排。
- 直接问某日期或周次课表。

MCP 数据推理题：

- 需要统计某学期课程数量、成绩分布或考试数量。
- 需要筛选某天是否有课、某课程是否出现、某考试是否集中。
- 需要根据工具返回数据给出判断结论。

这些样本的标准答案不是凭空写入，而是实际调用当前系统中的 3 个 MCP 工具后生成：

- `score_query`
- `exam_query`
- `schedule_query`

Baseline-A/B/C 均不接入 MCP，因此这些题用于体现完整 System 的动态数据优势。

### 5.5 无答案样本构建

无答案样本为明显超出当前高校辅导员知识库或 MCP 工具边界的问题。例如不属于学校事务、不在知识库覆盖范围、无法通过教务工具查询的问题。

这些样本用于测试：

- 系统是否能合理拒答。
- 是否会在没有证据时强行生成。
- CRAG 与 Prompt 是否能控制幻觉。

## 6. 评测方法

每条样本按组别独立运行，记录以下内容：

- `variant`：实验组名称。
- `question`：问题。
- `answer`：模型回答。
- `retrievedChunks`：召回证据。
- `cragAction`：System 的 CRAG 动作。
- `retrieval`：Hit Rate、MRR、Recall、Precision。
- `judge`：Correctness、Faithfulness、Relevance、FallbackAppropriate、Hallucination、IssueType。

评分方式采用 LLM-as-Judge。评审输入包括：

- 用户问题。
- 标准答案。
- 是否应无答案。
- 检索证据摘要。
- 系统回答。

评审输出结构化 JSON，便于自动统计。Correctness >= 4 视为回答正确。

## 7. 实验运行流程

### 7.1 数据集检查

先确认数据集规模与分布：

```bash
jq '{total:(.cases|length), kb:([.cases[] | select(.expectedNoAnswer == false and (.questionType != "tool_mcp" and .questionType != "tool_mcp_reasoning"))] | length), mcp_direct:([.cases[] | select(.questionType == "tool_mcp")] | length), mcp_reasoning:([.cases[] | select(.questionType == "tool_mcp_reasoning")] | length), no_answer:([.cases[] | select(.expectedNoAnswer == true)] | length)}' evaluation-output/rag-experiment/manual_eval_dataset.json
```

### 7.2 Java 主实验

小样本快速验证：

```bash
mvn -q -Drag.eval.enabled=true -Dtest=org.buaa.rag.experiment.RagExperimentManualTest#runSmallComparison test
```

正式全量实验：

```bash
mvn -q -Drag.eval.enabled=true -Dtest=org.buaa.rag.experiment.RagExperimentManualTest#runFourWayComparison test
```

输出文件：

- `evaluation-output/rag-experiment/comparison_result.json`
- `evaluation-output/rag-experiment/comparison_result.csv`
- `evaluation-output/rag-experiment/comparison_report.md`
- 运行中每 30 条记录输出 partial checkpoint。

## 8. 结果分析方式

实验完成后按以下顺序分析：

1. 先看检索指标：System 的 Hit Rate、MRR、Recall 是否高于 Baseline-A/B/C。
2. 再看生成指标：System 的 Faithfulness 是否高，Hallucination Rate 是否低。
3. 再看端到端：System 的 Correctness 和 Answer Accuracy 是否最高。
4. 单独分析 MCP 题：Baseline-A/B/C 因未接入 MCP 应无法回答，System 应正确回答。
5. 单独分析无答案题：System 应有更高 Fallback Success Rate，且不应强答。
6. 查看 Bad Case：按 issueType 区分 retrieval、generation、fallback、runtime 等问题。

论文中建议用总表展示主指标，再用 Bad Case 表说明各 baseline 的典型缺陷。

## 9. 当前小样本链路检查结果

在 40 条小样本 checkpoint 上，当前结果主要用于检查实验链路是否能跑通，并定位下一轮优化方向：

| Variant | HitRate | Recall | Correctness | Answer Accuracy | Hallucination |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline-A | 0.0% | 0.0% | 1.40 | 6.7% | 20.0% |
| Baseline-B | 20.0% | 20.0% | 4.27 | 76.7% | 53.3% |
| Baseline-C | 13.3% | 13.3% | 1.47 | 10.0% | 13.3% |
| System | 50.0% | 50.0% | 2.73 | 40.0% | 46.7% |

该小样本不是最终论文结果。它说明当前 System 的检索 HitRate/Recall 已高于 Baseline-A/B/C，但端到端 Correctness 和 Answer Accuracy 还没有达到论文目标，需要继续优化证据保留、CRAG 决策、Prompt 约束和 MCP/无答案样本处理后再跑正式 500 条结果。正式结论以全量数据集为准。

## 10. 预期论文论证点

- Baseline-A 说明纯关键词检索无法覆盖语义表达多样的问题。
- Baseline-B 说明单路向量检索可能提高部分语义召回，但缺少意图约束、CRAG 和重排时容易产生幻觉。
- Baseline-C 说明普通 Hybrid+RRF 不是充分条件，混合召回仍需要意图路由、rerank、CRAG 和结构化 Prompt 才能稳定提升端到端答案。
- System 的优势来自组合链路：结构感知 chunk、查询改写、意图树、多通道检索、交叉编码重排、CRAG 质量评估、MCP 工具路由和证据约束生成。

## 11. 后续优化闭环

正式实验前后按以下闭环继续优化：

1. 跑 small/partial 实验。
2. 查看指标和 Bad Case。
3. 如果 Hit Rate/Recall 低，优先调 topK、召回倍数、意图候选数和重排。
4. 如果 Faithfulness 低或幻觉率高，优先调 Prompt、CRAG 阈值和证据截断长度。
5. 如果无答案题错误，优先调 CRAG 和兜底策略。
6. 如果 MCP 题失败，检查 cookie、参数提取和工具返回。
7. 复跑并确认 System 在主要指标上领先所有 baseline。
