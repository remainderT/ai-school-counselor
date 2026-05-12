# ai-school-counselor-rag-eval 实验设计

## 1. 实验目标
- 验证高校辅导员知识库场景下，完整优化版 RAG 相比两类基线方案在检索效果与端到端回答质量上的改进。
- 分离分析“召回能力提升”和“最终回答质量提升”之间的关系，避免只看生成结果而忽略检索链路贡献。
- 观察 CRAG 兜底、混合检索、结构感知分块等模块对幻觉控制与拒答策略的实际影响。

## 2. 对照组设置

| 组别 | 检索方式 | 分块方式 | 排序/融合 | 生成策略 |
| --- | --- | --- | --- | --- |
| Baseline-A | BM25 关键词检索 | 原始线上 chunk | 无向量召回、无重排 | 检索后直接生成 |
| Baseline-B | 单路向量检索 | 固定 512 字符重分块 | 无混合检索、无 CRAG | 朴素 RAG 生成 |
| System | 混合检索 | 结构感知分块 | 意图路由 + 融合 + 重排 + CRAG | 结构化提示生成 |

## 3. 语料与评测集
- 语料来源：直接读取线上 MySQL 知识库，并复用系统当前 chunk 数据。
- 当前快照统计：知识库 9 个，文档 423 篇，chunk 1311 条。
- 数据集名称：`ai-school-counselor-rag-eval`。
- 评测集规模：36 条，其中可回答样本 36 条，无答案样本 0 条。
- 正样本构建：从高质量真实 chunk 中进行分层抽样，调用大模型弱监督生成问题与标准答案，并保留 chunk 级证据标注。
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
4. 运行 `runThreeWayComparison()` 执行 Baseline-A、Baseline-B、System 三组对照实验。
5. 汇总输出 `comparison_report.md`、`comparison_result.json` 与 `comparison_result.csv`。

## 6. 当前优化假设
- 将 `min-acceptable-score` 下调，可减少相关片段在后处理阶段被过早过滤导致的错误拒答。
- 提高单条参考片段长度与来源上限，可降低长规则、表格型制度文本在答案阶段被截断的信息损失。
- 若优化后检索指标和 Correctness 同步提升，可证明当前系统瓶颈主要位于“证据保留不足”而非“检索框架设计失效”。
