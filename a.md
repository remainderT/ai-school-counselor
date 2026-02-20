# Spring AI 中 Document Metadata 的作用与项目落地

## 1. 一句话结论
在 Spring AI 体系里，`content` 决定“语义像不像”，`metadata` 决定“该不该检索、能不能返回、如何解释来源”。  
所以 `metadata` 不是附属信息，而是 RAG 工程可用性的核心。

## 2. Metadata 在 Spring AI 架构中的位置
`Document` 的基本结构是：
- 文本内容：`content`
- 元数据：`Map<String, Object> metadata`

典型流水线：
1. `DocumentReader` 读入原始文档（可带基础元信息，如文件名、页码）。
2. `DocumentTransformer` 切块/清洗（可继续补充 chunk 级信息）。
3. `DocumentWriter` 写入 `VectorStore`（metadata 会被同步索引）。
4. 检索时通过 `SearchRequest.filterExpression(...)` 做元数据过滤。
5. 生成答案时把关键 metadata 作为引用信息返回给前端。

## 3. Metadata 的 4 个核心价值

### 3.1 精确过滤（最关键）
只靠向量相似度会出现“语义近但业务错”的结果。  
metadata 让你在检索阶段加硬约束，例如学院、文档类型、年份。

```java
SearchRequest request = SearchRequest.builder()
    .query("国家奖学金申请条件")
    .topK(5)
    .filterExpression("doc_type == 'scholarship' && department in ['计算机学院','通用'] && policy_year >= '2024'")
    .build();
```

### 3.2 上下文增强
LLM 看到的不应只有片段正文，还应知道“这段话来自哪个文件、哪一年”。  
这能显著降低跨文档拼接导致的幻觉。

### 3.3 引用溯源与可解释性
在 chunk metadata 中保留 `source_file_name / md5_hash / fragment_index`，回答时就能回传“证据来源”，支持前端展示引用。

### 3.4 流水线状态传递
metadata 还能承载 ETL 状态信息（页码、切分序号、token 数、是否截断等），方便排障和质量评估。

## 4. 结合你项目字段的最佳实践

建议的核心字段与用途：

| 字段 | 作用 | 典型使用时机 |
|---|---|---|
| `doc_type` | 限制业务类别（奖学金/请假/保研） | 检索前过滤 |
| `department` | 限制学院或“通用”政策范围 | 检索前过滤/权限校验 |
| `policy_year` | 保证时效性，避免旧政策污染 | 检索前过滤/排序加权 |
| `tags` | 提升长尾召回能力 | 检索过滤/重排加权 |
| `md5_hash` | 文档主键与溯源锚点 | 检索后回填来源 |
| `fragment_index` | 原文定位 | 引用展示 |

## 5. 你当前代码中的对应点（已具备与待完善）

### 已具备
1. 上传接口已经接收元数据参数：  
   `DocumentServiceImpl.upload(..., department, docType, policyYear, tags)`
2. 查询侧已经有元数据过滤对象：  
   `MetadataFilter` + `QueryAnalysisServiceImpl.resolveFilter(...)`
3. 检索后有元数据匹配逻辑：  
   `SmartRetrieverServiceImpl.matchesMetadata(...)`

### 待完善（关键）
目前文档入库阶段还没有把 `department/docType/policyYear/tags` 真正写入 `DocumentDO`，也没有带入 ES chunk 索引模型。  
这会导致你只能“检索后过滤”，而不是“检索前过滤”，准确率和性能都会受影响。

## 6. 推荐落地方案

### 方案 A（建议优先，最小改动）
保留你现有 ES 检索链路，先补齐元数据落库和索引字段：
1. 上传时把 `department/docType/policyYear/tags` 写入 `document` 表。
2. 构建 `IndexedContentDO` 时，把这些字段冗余到 chunk 索引。
3. 在 ES 查询 DSL 中直接加入过滤条件（pre-filter）。

优点：改动小、见效快，和当前代码结构完全兼容。

### 方案 B（Spring AI 标准化）
引入 `VectorStore` + `Document(metadata)` 统一写读，使用 `filterExpression` 做跨向量库可移植过滤。

优点：抽象统一、后续更易切换底层存储。  
代价：需要对现有 ES client 检索代码做一轮重构。

## 7. 评审口径（毕设答辩可直接使用）

可以用这句话总结：

> 我们将元数据作为 RAG 的结构化约束层：先用 metadata 过滤候选，再做向量相似度匹配，最后输出可追溯引用。这样同时提升了准确率、时效性与可解释性，并降低了检索延迟与幻觉风险。

## 8. 下一步执行清单
1. 补齐 `DocumentServiceImpl.upload` 的元数据持久化。
2. 给 `IndexedContentDO` 增加 `docType/department/policyYear/tags/sourceFileName` 字段。
3. 在检索请求中把 `MetadataFilter` 下推到 ES 查询阶段，而不是只做检索后过滤。
4. 输出回答时携带 `source_file_name + md5_hash + fragment_index + score`，形成标准引用结构。

