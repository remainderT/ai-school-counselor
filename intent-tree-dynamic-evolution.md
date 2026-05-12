# 意图树动态迭代与知识库推荐方案

## 1. 背景

当前项目中的意图树节点主要依赖人工维护，适合冷启动和强可控场景，但随着文档规模和真实对话量上升，会逐渐暴露出几个问题：

1. 新问题类型发现慢，线上只能命中已有节点。
2. 文档上传必须人工选择知识库，运营成本较高。
3. 会话日志、fallback、低置信路由等高价值信号尚未进入“树迭代”闭环。
4. 正式意图树和真实用户表达之间会逐步偏离。

本文件给出一套适合当前项目架构的演进方案：在保留正式意图树稳定性的前提下，引入“候选发现层 + 人审发布层”，实现根据上传文档和对话历史动态迭代。

## 2. 项目当前实现现状

结合当前仓库代码，可以看到这几个关键基础能力已经具备：

### 2.1 意图树已具备在线加载与刷新能力

正式意图树由 `intent_node` 表加载，构建为在线快照，并支持缓存和刷新：

- [IntentTreeSnapshotService.java](/Users/yushuhao/Graduation/ai-school-counselor/src/main/java/org/buaa/rag/core/online/intent/IntentTreeSnapshotService.java:56)
- [IntentTreeServiceImpl.java](/Users/yushuhao/Graduation/ai-school-counselor/src/main/java/org/buaa/rag/service/impl/IntentTreeServiceImpl.java:38)

这意味着后续只要把“候选节点审核通过”写回正式表，再调用刷新逻辑即可上线新树。

### 2.2 文档上传与知识库入库链路已经存在

当前上传流程会校验 `knowledgeId`，然后完成文档持久化与离线摄取：

- [DocumentServiceImpl.java](/Users/yushuhao/Graduation/ai-school-counselor/src/main/java/org/buaa/rag/service/impl/DocumentServiceImpl.java:109)
- [KnowledgeServiceImpl.java](/Users/yushuhao/Graduation/ai-school-counselor/src/main/java/org/buaa/rag/service/impl/KnowledgeServiceImpl.java:37)

现状问题是：上传前必须人工选知识库，还没有“知识库推荐”层。

### 2.3 会话历史和摘要能力已经可复用

当前会话上下文支持历史消息和摘要并行加载：

- [ConversationMemoryServiceImpl.java](/Users/yushuhao/Graduation/ai-school-counselor/src/main/java/org/buaa/rag/core/online/memory/ConversationMemoryServiceImpl.java:84)

这说明你已经有条件抽取：

- 高频问法
- 长尾新问法
- 多轮追问主题
- 摘要级主题漂移

### 2.4 Trace 查询能力已经具备

当前已支持按 trace/run/node 查询链路执行信息：

- [RagTraceQueryServiceImpl.java](/Users/yushuhao/Graduation/ai-school-counselor/src/main/java/org/buaa/rag/service/impl/RagTraceQueryServiceImpl.java:36)

这对后续构造动态迭代样本非常关键，因为可以沉淀：

- 命中节点
- 路由耗时
- fallback / 弱命中
- 多候选冲突

## 3. 核心判断

最推荐的方向不是“让线上意图树自动改自己”，而是：

1. 线上保留稳定的正式意图树。
2. 离线持续发现候选新意图、候选知识库归属、候选节点拆分合并建议。
3. 通过审核发布机制，把候选结果合并进正式树。

这比“全自动在线改树”更适合高校问答 / RAG / 办事场景，原因有三点：

1. 学校制度和办事流程有强约束，错误路由代价高。
2. 文档本身具有版本性，很多“新意图”其实是“旧意图下的新制度文档”。
3. 运营上通常需要可解释、可回滚、可审计。

## 4. 推荐总体架构

建议把系统拆成四层：

### 4.1 正式服务层

继续沿用当前 `intent_node -> snapshot -> retrieval` 的正式链路，保证线上稳定。

### 4.2 候选发现层

从两个入口持续产出候选：

1. 文档入口
2. 对话入口

产出物包括：

- 候选知识库
- 候选父节点
- 候选新意图节点
- 候选关键词
- 候选代表问句

### 4.3 审核发布层

由后台页面或管理接口完成：

- 接受候选
- 合并候选
- 拆分候选
- 拒绝候选
- 发布到正式树

### 4.4 反馈学习层

把审核结果和线上真实效果反哺回来，更新：

- 文档知识库推荐模型
- 新意图发现阈值
- 聚类策略
- 关键词生成质量

## 5. 文档驱动的知识库推荐

### 5.1 目标

用户上传文件时，不要求先精确知道它应该进入哪个知识库，而是由系统给出 `Top-K` 推荐。

### 5.2 推荐输入特征

建议抽取以下信息：

1. 文件名
2. 文档标题
3. 首段 / 摘要
4. 高频关键词
5. 正文 chunk embedding
6. OCR / 元数据中的部门名、制度名、时间范围

### 5.3 推荐方法

第一阶段优先采用“可快速落地”的无监督混合打分：

1. `embedding 相似度`
文档摘要向量和知识库主题画像向量的相似度。

2. `关键词匹配`
如“学籍”“选课”“缓考”偏向教务，“奖学金”“综测”偏向学生事务。

3. `规则特征`
发文部门、文件格式、来源目录、URL 域名。

4. `LLM 重排序`
把 `Top-N` 候选知识库交给 LLM 解释性排序，输出推荐理由。

建议最终分数：

`score = 0.55 * embedding + 0.20 * keyword + 0.15 * metadata + 0.10 * llm_rerank`

### 5.4 知识库画像如何构建

每个知识库维护一个主题画像：

- 名称
- 描述
- 已有文档摘要集合
- 代表关键词
- 代表 chunk 向量中心
- 历史命中问句集合

这些信息可以由离线任务每日重建一次。

### 5.5 产品表现建议

上传文档后返回：

- 推荐知识库 `Top-3`
- 每个候选置信度
- 推荐原因
- 是否建议新建知识库

例如：

```text
推荐知识库:
1. 教务教学 0.89
2. 综合规章 0.61
3. 学生事务与奖助 0.23

理由:
- 文档标题包含“本科生学籍管理规定”
- 摘要中高频出现“休学、复学、注册、学分”
- 与“教务教学”知识库已有文档中心相似度最高
```

## 6. 对话驱动的动态意图树迭代

### 6.1 可作为样本源的数据

建议优先吃这几类会话样本：

1. fallback 问句
2. 低置信路由问句
3. 多候选分数接近的冲突问句
4. 人工纠正问句
5. 多轮追问后才命中的问句
6. 引发长对话但检索效果差的问句

### 6.2 离线任务流程

#### 第一步：样本收集

从消息表、trace、检索结果中抽取候选 query，并打上特征：

- sessionId
- userQuery
- predictedIntent
- confidence
- candidateIntents
- retrievedKnowledgeIds
- fallbackFlag
- answerSatisfactionProxy

#### 第二步：语义归一

对 query 做清洗：

- 去口语噪音
- 指代消解
- 同义表达归并
- 多轮补全为独立问句

#### 第三步：向量聚类

对候选 query embedding 后做聚类，可选：

- HDBSCAN
- Agglomerative Clustering
- KMeans 作为 baseline

其中 HDBSCAN 更适合开放类别发现，因为它能保留噪声点。

#### 第四步：簇级语义命名

对每个簇让 LLM 输出：

- 建议节点名
- 建议父节点
- 建议节点描述
- 建议关键词
- 代表问句
- 与现有节点的差异

#### 第五步：候选动作决策

每个簇最终进入四类动作之一：

1. `attach`
并入已有节点，只补充样本和关键词。

2. `split`
现有节点过大，建议拆分。

3. `new_leaf`
新增叶子节点。

4. `new_group`
当多个新叶子共享共同上位主题时，新增中间组节点。

#### 第六步：人工审核与发布

审核通过后写入正式 `intent_node`，刷新快照。

## 7. 推荐的“影子树”机制

为了避免直接污染正式树，建议新增一棵“影子树”。

### 7.1 影子树用途

影子树只做候选验证，不直接影响线上路由：

- 演练新节点
- A/B 对比新旧路由
- 比较候选节点覆盖率
- 观察误召回

### 7.2 影子树上线方式

路由时同时计算：

1. 正式树结果
2. 影子树结果

但线上只采用正式树，影子树结果仅用于埋点分析。

这样可以安全回答两个问题：

1. 新节点是否真的能吸收原先 fallback？
2. 新节点是否会误吞已有成熟节点流量？

## 8. 数据表建议

建议新增以下表，而不是直接修改 `intent_node` 语义：

### 8.1 `intent_candidate_node`

字段建议：

- `id`
- `candidate_name`
- `candidate_type`
- `suggested_parent_id`
- `description`
- `keywords_json`
- `representative_queries_json`
- `source_type`
- `cluster_id`
- `status`
- `review_comment`
- `published_intent_node_id`
- `created_at`

### 8.2 `intent_candidate_sample`

- `id`
- `candidate_node_id`
- `session_id`
- `message_id`
- `query_text`
- `trace_id`
- `route_confidence`
- `fallback_flag`

### 8.3 `document_kb_recommendation`

- `id`
- `document_id`
- `recommended_kb_id`
- `rank_no`
- `score`
- `reason`
- `accepted_flag`

### 8.4 `routing_feedback`

- `id`
- `trace_id`
- `query_text`
- `predicted_intent_id`
- `accepted_intent_id`
- `feedback_type`
- `operator_id`

## 9. 和当前项目最匹配的分阶段实施方案

### Phase 1：低风险增量版本

目标：先做“推荐”和“候选”，不碰线上主链路。

包括：

1. 上传文档时返回知识库推荐 `Top-3`
2. 沉淀低置信 / fallback 问句池
3. 增加候选节点表
4. 做一个离线聚类脚本或定时任务

### Phase 2：管理台审核发布

目标：让运营可用。

包括：

1. 候选节点列表
2. 候选节点详情
3. 接受 / 拒绝 / 合并 / 拆分
4. 发布到正式树

### Phase 3：影子树评估

目标：验证新树是否比旧树更好。

包括：

1. 正式树 / 影子树双路计算
2. 比较覆盖率、fallback 率、纠错率
3. 输出发布建议

### Phase 4：闭环学习

目标：把审核结果持续反馈给推荐和发现模块。

包括：

1. 文档推荐模型重训练
2. 新意图发现阈值优化
3. 关键词和代表问句自动更新

## 10. 论文与研究方向

下面几篇论文和你的场景关联度最高。

### 10.1 新意图发现

1. Zhang et al., ACL 2022  
[New Intent Discovery with Pre-training and Contrastive Learning](https://aclanthology.org/2022.acl-long.21/)

适用点：

- 从未标注用户问句中发现新意图
- 强调表示学习和聚类质量

2. Mou et al., COLING 2022  
[Generalized Intent Discovery: Learning from Open World Dialogue System](https://aclanthology.org/2022.coling-1.59/)

适用点：

- 已知意图和未知意图共存
- 更接近真实线上开放世界场景

3. Song et al., Findings EMNLP 2023  
[Continual Generalized Intent Discovery: Marching Towards Dynamic and Open-world Intent Recognition](https://aclanthology.org/2023.findings-emnlp.289/)

适用点：

- 持续发现动态数据流中的新意图
- 很贴近“意图树持续迭代”的目标

4. Tang et al., ACL 2024  
[Learning Geometry-Aware Representations for New Intent Discovery](https://aclanthology.org/2024.acl-long.306/)

适用点：

- 提升新意图聚类分离度
- 适合后续优化候选簇质量

### 10.2 层级分类与树状标签

5. Chen et al., ACL-IJCNLP 2021  
[Hierarchy-aware Label Semantics Matching Network for Hierarchical Text Classification](https://aclanthology.org/2021.acl-long.337/)

适用点：

- 解决文本到层级标签的匹配问题
- 对“意图树路由”比普通平面分类更有参考价值

### 10.3 对本项目最有启发的研究结论

这些论文综合起来支持一个判断：

1. 新意图发现可以做，但更适合放在离线流程。
2. 层级结构比平面 intent list 更适合复杂校园服务域。
3. 连续增量学习比一次性聚类更贴近真实系统。
4. “发现新意图”与“决定是否改树”不是同一个问题，后者必须结合业务治理。

## 11. 工业界主流做法

工业界目前更成熟的不是“自动改树”，而是“会话挖掘 + 候选提案 + 人工审核”。

### 11.1 Rasa：Conversation-Driven Development

官方建议持续回看真实会话，重点关注 `out_of_scope`、fallback 和误分类案例，并把这些样本转化为训练数据和新能力需求。

参考：

- [Conversation-Driven Development](https://rasa.com/docs/rasa/next/conversation-driven-development/)
- [Conversation Review](https://rasa.com/docs/studio/analyze/conversation-review)

对本项目的启发：

- 真实会话是最好的新意图来源
- fallback 不一定是坏事，它是新能力需求信号
- 最好给会话加标签和审核流

### 11.2 Dialogflow CX：迭代式主题组织 + Data Store

Google 官方建议大型 agent 先按顶层请求搭骨架，再迭代完善；同时支持 `data store` 挂接文档、网站和第三方系统，并支持刷新机制。

参考：

- [General agent design best practices](https://cloud.google.com/dialogflow/cx/docs/concept/agent-design)
- [Data stores](https://cloud.google.com/dialogflow/cx/docs/concept/data-store)

对本项目的启发：

- 主题分层应迭代建设，而不是一口气设计完
- 文档知识库本身要支持增量更新和刷新
- 知识库和会话结构最好协同演进

### 11.3 Amazon Lex：生成式扩充样本语料

Amazon Lex 已支持依据意图名称、描述和已有样本自动生成 sample utterances。

参考：

- [Use utterance generation to generate sample utterances for intent recognition](https://docs.aws.amazon.com/lexv2/latest/dg/utterance-generation.html)

对本项目的启发：

- 当候选节点生成后，可以自动扩写代表问句
- 生成式方法更适合作为“扩充样本”而不是“直接定义最终节点”

### 11.4 Google Document AI：文档分类器

Google Document AI 的自定义文档分类能力支持 few-shot、fine-tuning 和 auto-labeling。

参考：

- [Create, use, and manage a custom document classifier](https://cloud.google.com/document-ai/docs/custom-classifier)

对本项目的启发：

- “上传文档推荐知识库”本质上就是文档分类问题
- 可先从规则 + embedding 做起，后续再升级为监督模型

### 11.5 Azure CLU：迭代标注训练

Azure CLU 仍然代表典型的“标注 utterance -> 训练 -> 评估 -> 迭代”流程，但官方页面当前已明确写明该能力将于 `2029-03-31` 退役，新项目建议迁移到 Foundry 体系。

参考：

- [What is conversational language understanding?](https://learn.microsoft.com/en-us/azure/ai-services/language-service/conversational-language-understanding/overview)

对本项目的启发：

- 人工审核和持续训练仍然是企业级 NLU 的主流治理方式
- 工业界并不倾向于让正式意图体系完全自发演化

## 12. 对当前项目的最终建议

如果只选一条最稳、最适合你当前代码结构的路线，我建议是：

1. 先补“上传文档知识库推荐”。
2. 再做“fallback / 低置信 query 候选池”。
3. 再做“离线聚类 -> 候选节点生成”。
4. 最后做“审核发布到正式树”。

也就是说，先把“发现能力”做出来，再把“自动改树”降级成“辅助运营改树”。

这样做的好处是：

1. 对现有线上链路侵入最小。
2. 容易验证收益。
3. 容易做灰度和回滚。
4. 后续如果效果好，再逐步引入影子树和半自动发布。

## 13. 下一步可直接拆的开发任务

建议优先拆成下面几个工程任务：

1. 新增文档知识库推荐服务 `KnowledgeRecommendationService`
2. 为上传接口增加 `recommendKnowledgeIds` 返回字段
3. 新增路由反馈与候选样本表
4. 新增离线任务：聚类 fallback / 低置信 query
5. 新增候选节点生成服务
6. 新增后台审核发布接口
7. 发布后刷新 `IntentTreeSnapshotService`

---

如果需要，下一步可以继续输出一份更工程化的文档，专门写：

1. 表结构 SQL
2. Spring Boot 模块划分
3. 接口定义
4. 定时任务设计
5. 候选节点审核发布流程图
