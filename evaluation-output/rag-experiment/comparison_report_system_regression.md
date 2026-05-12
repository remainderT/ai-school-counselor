# RAG 三组对照实验报告

## 实验设计
- Baseline-A：纯 BM25 关键词检索 + 直接生成。
- Baseline-B：朴素 RAG，使用固定 512 字符重分块 + 单路向量检索。
- System：复用当前系统的结构感知分块、多通道混合检索、重排和 CRAG 评估。

## 数据集
- 数据集名称：ai-school-counselor-rag-eval-system-regression
- 样本总数：36
- 可回答样本：36
- 无答案样本：0

## 汇总指标

| Variant | HitRate | MRR | Recall | Precision | Correctness | Faithfulness | Relevance | Accuracy | Hallucination | Fallback |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| System | 19.4% | 0.139 | 19.4% | 3.6% | 1.72 | 3.25 | 2.25 | 13.9% | 22.2% | 0.0% |

## 典型问题归因

### Baseline-A

### Baseline-B

### System
- 问题：‘我的资源盾牌’活动结束后，带领者强调这些资源的核心作用是什么？
  结论：检索失败，未找到相关活动信息；问题类型：retrieval
- 问题：本科生中英文成绩单翻译申请，哪些年级的学生必须通过线上系统申请，哪些可以自助打印？
  结论：检索失败，未找到相关答案；问题类型：retrieval
- 问题：在图书馆阅览时，对携带书刊数量有建议吗？
  结论：回答编造具体册数且无证据支持；问题类型：generation

## 论文撰写建议
- 正式论文建议优先使用 manual_eval_dataset.json，经人工复核后再跑最终分数。
- 若 Baseline-B 在 HitRate 提升但 Faithfulness 下降，可据此论证“仅增大召回不足以提升端到端效果”。
- 若 System 的 Hallucination 更低、Fallback 更稳，可突出 CRAG 和混合检索的贡献。
