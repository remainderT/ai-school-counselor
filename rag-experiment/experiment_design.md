# ai-school-counselor-rag-eval 实验设计

## 1. 实验目标
- 验证高校辅导员知识库场景下，完整优化版 RAG 相比朴素检索、朴素向量 RAG 与普通 Hybrid+RRF 基线在检索效果与端到端回答质量上的改进。
- 分离分析“召回能力提升”和“最终回答质量提升”之间的关系，避免只看生成结果而忽略检索链路贡献。
- 观察 CRAG 兜底、混合检索、结构感知分块、意图路由和 MCP 工具调用等模块对幻觉控制、动态查询与拒答策略的实际影响。

## 2. 对照组设置

| 组别 | 检索方式 | 分块方式 | 排序/融合 | 生成策略 |
| --- | --- | --- | --- | --- |
| Baseline-A | BM25 关键词检索 | 原始线上 chunk | 无向量召回、无重排 | 检索后直接生成 |
| Baseline-B | 单路向量检索 | 固定 512 字符重分块 | 无混合检索、无 CRAG | 朴素 RAG 生成 |
| Baseline-C | 全局 BM25 + 向量检索 | 原始线上 chunk | RRF 融合，无意图树、无 CRAG、无交叉编码重排 | 检索后直接生成 |
| System | 混合检索 + MCP 工具 | 结构感知分块 | 意图路由 + 融合 + 重排 + CRAG | 结构化提示生成 / 工具结果生成 |

## 3. 语料与评测集
- 语料来源：直接读取线上 MySQL 知识库，并复用系统当前 chunk 数据。
- 当前快照统计：知识库 9 个，文档 415 篇，chunk 1313 条。
- 数据集名称：`ai-school-counselor-rag-eval`。
- 评测集规模：500 条，其中可回答样本 460 条，MCP 直接工具样本 20 条，MCP 数据推理样本 20 条，无答案样本 40 条。
- 正样本构建：从高质量真实 chunk 中进行分层抽样，优先让每个文档/片段贡献 1 条题，再用后续候选题补齐规模，并保留 chunk 级证据标注。
- 工具样本构建：按成绩、考试、课表 3 个 MCP 工具的真实参数能力设计直接查询题，并实际调用工具生成标准答案。
- MCP 推理样本构建：基于 MCP 工具返回数据设计统计、比较、判断、筛选类问题，标准答案由工具结果约束生成。
- 负样本构建：人工设计明显超出高校辅导员知识库边界的问题，用于检验系统拒答与兜底能力。
- 正式论文建议：先在 `manual_eval_dataset.json` 上人工复核，再跑最终结果。

## 4. 评价指标
- 检索指标：HitRate、MRR、Recall、Precision。
- 生成指标：Correctness、Faithfulness、Relevance。
- 稳健性指标：Answer Accuracy、Hallucination Rate、Fallback Success Rate。
- 评审方式：结合问题、标准答案、检索证据与系统回答，由 LLM 评审器输出结构化分数，再统计各组均值。

## 5. 实验流程
1. 运行 `exportCorpusSnapshot()` 导出知识库快照与人工标注模板。
2. 运行 `buildAutoEvaluationDataset()` 生成 200+ 条自动评测集。
3. 抽查并修订 `manual_eval_dataset.json` 中的问题、答案和无答案标签。
4. 运行 `runFourWayComparison()` 执行 Baseline-A、Baseline-B、Baseline-C、System 四组主实验；旧入口 `runThreeWayComparison()` 继续兼容。
5. 汇总输出 `comparison_report.md`、`comparison_result.json` 与 `comparison_result.csv`。

## 6. 当前优化假设
- 将 `min-acceptable-score` 下调，可减少相关片段在后处理阶段被过早过滤导致的错误拒答。
- 提高单条参考片段长度与来源上限，可降低长规则、表格型制度文本在答案阶段被截断的信息损失。
- MCP 直接工具与数据推理样本只对完整系统开放，能够证明系统不是单纯知识库检索，而是静态知识 + 动态教务数据的综合问答。
- 若优化后检索指标和 Correctness 同步提升，可证明当前系统瓶颈主要位于“证据保留不足”而非“检索框架设计失效”。

## 7. 半量实验结果（250 题）

### 7.1 主实验口径说明
- 实验数据集：`evaluation-output/rag-experiment/manual_eval_dataset.json`
- 半量样本数：250 题，对应 1000 条运行记录（4 组主实验各 250 条）
- 数据组成：230 条可回答题，10 条 MCP 直接题，10 条 MCP 推理题，20 条无答案题
- 主实验结果文件：
  - `evaluation-output/rag-experiment/comparison_result_half.json`
  - `evaluation-output/rag-experiment/comparison_report_half.md`

### 7.2 Baseline-A / B / C / System 主结果

| Variant | Sample | HitRate | MRR | Recall | Precision | Correctness | Faithfulness | Relevance | Answer Accuracy | Hallucination | Fallback |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline-A | 250 | 0.0% | 0.000 | 0.0% | 0.0% | 1.744 | 3.224 | 2.860 | 14.4% | 34.0% | 100.0% |
| Baseline-B | 250 | 17.0% | 0.072 | 17.0% | 3.39% | 3.620 | 3.720 | 4.160 | 61.6% | 43.6% | 100.0% |
| Baseline-C | 250 | 9.6% | 0.056 | 9.6% | 1.91% | 1.728 | 4.048 | 2.060 | 16.4% | 17.6% | 100.0% |
| System | 250 | 43.9% | 0.333 | 43.9% | 6.19% | 2.932 | 3.728 | 3.512 | 42.0% | 28.0% | 95.0% |

结论：
- 从检索链路看，`System` 在 `HitRate / MRR / Recall / Precision` 四项上均为当前最优，说明多通道检索、意图路由与重排的召回能力明显强于三组内部 baseline。
- 从端到端总题正确率看，当前轮次最高的是 `Baseline-B`，`Answer Accuracy = 61.6%`；`System` 为 `42.0%`，尚未达到论文预期的 `75%` 左右目标。
- 从幻觉控制看，`System` 的 `Hallucination Rate = 28.0%`，优于 `Baseline-B` 的 `43.6%`，但仍有下降空间。

### 7.3 分题型观察

按照半量主实验结果统计：

- `System` 在无答案题上的 `Answer Accuracy = 90.0%`，优于需要强答的朴素 RAG 方案，说明 `CRAG + 兜底策略` 已经起到明显作用。
- `System` 在 `tool_mcp` 题上的正确率为 `30.0%`，在 `tool_mcp_reasoning` 题上的正确率为 `10.0%`。这说明系统已经具备动态工具能力，但工具路由、参数提取和结果整合还不够稳定。
- `Baseline-B` 虽然总题正确率最高，但在 `tool_mcp` 与 `tool_mcp_reasoning` 两类题上均接近不可用，本质上仍是“静态知识答得像，动态问题答不了”。

### 7.4 当前失分原因

从 `System` 的 bad case 看，当前主要短板不是“完全检不到”，而是以下三类问题：

1. `CRAG` 对低证据密度题目偏保守，已经检到部分相关片段时仍容易转成 `NO_ANSWER / REFINE`。
2. 低分过滤和重排后处理对精确字段题过于激进，像发票抬头、交货期、地点、比例数字这类题有时会被压缩到 1 到 3 条证据，导致最终回答不稳。
3. `MCP` 题的工具路由和结果整合仍有失败样例，出现“工具执行失败但应当可答”的情况。

### 7.5 论文写法建议

本轮半量实验适合在论文中写成“中期对照结果”，结论建议如下：

- 可以明确写出：当前系统在检索指标上已经全面超过内部 baseline，说明检索链路优化有效。
- 也需要如实写出：当前系统端到端总题正确率尚未达到目标值，说明“高召回”还没有完全转化成“高正确回答率”。
- 当前论文主实验建议只保留 `Baseline-A / Baseline-B / Baseline-C / System` 四组，保证口径统一。
