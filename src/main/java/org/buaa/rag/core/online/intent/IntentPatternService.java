package org.buaa.rag.core.online.intent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.buaa.rag.dao.entity.IntentNodeDO;
import org.buaa.rag.dao.mapper.IntentNodeMapper;
import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.offline.index.VectorEncoding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import org.buaa.rag.properties.IntentRoutingProperties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class IntentPatternService {

    private final ElasticsearchClient esClient;
    private final VectorEncoding vectorEncoding;
    private final IntentNodeMapper intentNodeMapper;
    private final ObjectMapper objectMapper;
    private final IntentRoutingProperties intentRoutingProperties;

    public IntentPatternService(ElasticsearchClient esClient,
                                VectorEncoding vectorEncoding,
                                IntentNodeMapper intentNodeMapper,
                                ObjectMapper objectMapper,
                                IntentRoutingProperties intentRoutingProperties) {
        this.esClient = esClient;
        this.vectorEncoding = vectorEncoding;
        this.intentNodeMapper = intentNodeMapper;
        this.objectMapper = objectMapper;
        this.intentRoutingProperties = intentRoutingProperties;
    }

    @Value("${elasticsearch.intent-index:intent_patterns}")
    private String intentIndex;

    private List<IntentSeed> seedPatterns;

    public Optional<IntentDecision> semanticRoute(String query) {
        try {
            ensureIndexAndSeeds();
            List<float[]> encoded = vectorEncoding.encode(List.of(query));
            if (encoded.isEmpty()) {
                return Optional.empty();
            }
            float[] vector = encoded.get(0);
            SearchResponse<Map> resp = esClient.search(s -> s
                            .index(intentIndex)
                            .knn(knn -> knn
                                    .field("vector")
                                    .queryVector(toList(vector))
                                    .k(3)
                                    .numCandidates(5)
                            )
                            .size(3),
                    Map.class
            );

            if (resp.hits().hits().isEmpty()) {
                return Optional.empty();
            }

            var hit = resp.hits().hits().get(0);
            double score = normalizeConfidence(hit.score());
            if (score < intentRoutingProperties.getSemanticMinScore()) {
                return Optional.empty();
            }

            Map<String, Object> source = hit.source();
            if (source == null) {
                return Optional.empty();
            }
            String level1 = stringVal(source.get("level1"));
            String level2 = stringVal(source.get("level2"));
            String intentCode = stringVal(source.get("intentCode"));
            String nodeType = stringVal(source.get("nodeType"));
            String toolName = normalizeText(stringVal(source.get("actionService")));

            IntentDecision.Action action = resolveAction(intentCode, level2, nodeType, toolName);

            IntentDecision.IntentDecisionBuilder builder = IntentDecision.builder()
                    .level1(level1)
                    .level2(level2)
                    .confidence(score)
                    .toolName(StringUtils.hasText(toolName) ? toolName : null)
                    .action(action);

            return Optional.of(builder.build());
        } catch (Exception e) {
            log.debug("语义路由失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void recordPattern(String level1, String level2, String query, double confidence) {
        try {
            ensureIndexAndSeeds();
            List<float[]> vecs = vectorEncoding.encode(List.of(query));
            if (vecs.isEmpty()) {
                return;
            }
            float[] vec = vecs.get(0);
            String intentCode = (level1 == null ? "unknown" : level1) + ":" + (level2 == null ? "unknown" : level2);
            esClient.index(IndexRequest.of(ir -> ir
                    .index(intentIndex)
                    .id(UUID.randomUUID().toString())
                    .document(Map.of(
                            "intentCode", intentCode,
                            "level1", level1,
                            "level2", level2,
                            "sample", query,
                            "nodeType", "RAG_QA",
                            "actionService", "",
                            "vector", toList(vec),
                            "confidence", confidence
                    ))
            ));
        } catch (Exception e) {
            log.debug("写回意图样本失败: {}", e.getMessage());
        }
    }

    public void initPatterns() {
        try {
            ensureIndexAndSeeds();
        } catch (Exception e) {
            log.warn("初始化意图索引失败: {}", e.getMessage());
        }
    }

    private void ensureIndexAndSeeds() throws IOException {
        loadSeedsIfNeeded();
        boolean exists = esClient.indices().exists(ExistsRequest.of(b -> b.index(intentIndex))).value();
        if (!exists) {
            createIndex();
        }
        if (indexIsEmpty()) {
            bulkSeed();
        }
    }

    private void createIndex() throws IOException {
        esClient.indices().create(CreateIndexRequest.of(c -> c.index(intentIndex)));
        esClient.indices().putMapping(PutMappingRequest.of(m -> m
                .index(intentIndex)
                .properties("intentCode", Property.of(p -> p.keyword(k -> k)))
                .properties("level1", Property.of(p -> p.keyword(k -> k)))
                .properties("level2", Property.of(p -> p.keyword(k -> k)))
                .properties("nodeType", Property.of(p -> p.keyword(k -> k)))
                .properties("actionService", Property.of(p -> p.keyword(k -> k)))
                .properties("sample", Property.of(p -> p.text(t -> t)))
                .properties("vector", Property.of(p -> p.denseVector(v -> v
                        .dims(vectorEncodingDimension())
                        .index(true)
                        .similarity("cosine")
                )))
        ));
        log.info("创建意图索引: {}", intentIndex);
    }

    private boolean indexIsEmpty() throws IOException {
        var count = esClient.count(c -> c.index(intentIndex)).count();
        return count == 0;
    }

    private void bulkSeed() throws IOException {
        if (seedPatterns == null || seedPatterns.isEmpty()) {
            log.warn("意图样本种子为空，跳过初始化写入 ES");
            return;
        }
        List<String> samples = seedPatterns.stream().flatMap(seed -> seed.samples().stream()).toList();
        List<float[]> vectors = vectorEncoding.encode(samples);

        List<BulkOperation> ops = new ArrayList<>();
        int idx = 0;
        for (IntentSeed seed : seedPatterns) {
            for (String sample : seed.samples()) {
                float[] vec = vectors.get(idx++);
                Map<String, Object> doc = Map.of(
                        "intentCode", seed.intentCode(),
                        "level1", seed.level1(),
                        "level2", seed.level2(),
                        "nodeType", seed.nodeType(),
                        "actionService", seed.actionService() == null ? "" : seed.actionService(),
                        "sample", sample,
                        "vector", toList(vec)
                );
                ops.add(BulkOperation.of(b -> b.index(op -> op
                        .index(intentIndex)
                        .id(UUID.randomUUID().toString())
                        .document(doc)
                )));
            }
        }

        BulkResponse resp = esClient.bulk(BulkRequest.of(b -> b.operations(ops)));
        if (resp.errors()) {
            log.warn("意图样本写入存在错误");
        } else {
            log.info("已写入意图样本 {} 条", ops.size());
        }
    }

    private int vectorEncodingDimension() {
        List<float[]> encoded = vectorEncoding.encode(List.of("probe"));
        if (encoded.isEmpty()) {
            return 0;
        }
        return encoded.get(0).length;
    }

    private void loadSeedsIfNeeded() {
        if (seedPatterns != null) {
            return;
        }
        List<IntentNodeDO> rows = intentNodeMapper.selectList(
                Wrappers.lambdaQuery(IntentNodeDO.class)
                        .eq(IntentNodeDO::getDelFlag, 0)
                        .eq(IntentNodeDO::getEnabled, 1)
                        .orderByAsc(IntentNodeDO::getCreateTime, IntentNodeDO::getId)
        );
        if (rows == null || rows.isEmpty()) {
            seedPatterns = List.of();
            log.warn("数据库中未找到有效意图节点（intent_node）");
            return;
        }

        Map<String, IntentNodeDO> nodeMap = new HashMap<>();
        for (IntentNodeDO row : rows) {
            if (row != null && StringUtils.hasText(row.getNodeId())) {
                nodeMap.put(row.getNodeId().trim(), row);
            }
        }

        List<IntentSeed> loaded = new ArrayList<>();
        for (IntentNodeDO row : rows) {
            if (row == null || !StringUtils.hasText(row.getNodeId())) {
                continue;
            }
            List<String> examples = mergeExamples(row.getExamplesJson(), row.getKeywordsJson());
            if (examples.isEmpty()) {
                continue;
            }
            loaded.add(new IntentSeed(
                    row.getNodeId().trim(),
                    resolveLevel1Name(row, nodeMap),
                    normalizeText(row.getNodeName()),
                    normalizeText(row.getNodeType()),
                    normalizeText(row.getActionService()),
                    examples
            ));
        }

        seedPatterns = Collections.unmodifiableList(loaded);
        log.info("从 intent_node 加载意图样本成功，意图数: {}, 样本数: {}",
                seedPatterns.size(),
                seedPatterns.stream().mapToInt(item -> item.samples().size()).sum());
    }

    private List<String> mergeExamples(String examplesJson, String keywordsJson) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.addAll(parseJsonArray(examplesJson));
        set.addAll(parseJsonArray(keywordsJson));
        if (set.isEmpty()) {
            return List.of();
        }
        return List.copyOf(set);
    }

    private List<String> parseJsonArray(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(content, new TypeReference<List<String>>() {
            });
            if (list == null || list.isEmpty()) {
                return List.of();
            }
            List<String> cleaned = new ArrayList<>();
            for (String item : list) {
                if (StringUtils.hasText(item)) {
                    cleaned.add(item.trim());
                }
            }
            return cleaned;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String resolveLevel1Name(IntentNodeDO node, Map<String, IntentNodeDO> nodeMap) {
        if (node == null) {
            return "";
        }
        IntentNodeDO current = node;
        while (true) {
            String parentId = normalizeText(current.getParentId());
            if (!StringUtils.hasText(parentId)) {
                return normalizeText(current.getNodeName());
            }
            IntentNodeDO parent = nodeMap.get(parentId);
            if (parent == null) {
                return normalizeText(current.getNodeName());
            }
            if (isRoot(parent)) {
                return normalizeText(current.getNodeName());
            }
            current = parent;
        }
    }

    private boolean isRoot(IntentNodeDO node) {
        if (node == null) {
            return false;
        }
        if ("root".equalsIgnoreCase(normalizeText(node.getNodeId()))) {
            return true;
        }
        return !StringUtils.hasText(node.getParentId());
    }

    private IntentDecision.Action resolveAction(String intentCode, String level2, String nodeType, String toolName) {
        if ("API_ACTION".equalsIgnoreCase(normalizeText(nodeType)) && StringUtils.hasText(toolName)) {
            return IntentDecision.Action.ROUTE_TOOL;
        }
        return IntentDecision.Action.ROUTE_RAG;
    }

    private double normalizeConfidence(Double rawScore) {
        if (rawScore == null) {
            return 0.0;
        }
        double score = rawScore;
        if (score > 1.0) {
            score = score / 2.0;
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    private List<Float> toList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float f : vector) {
            list.add(f);
        }
        return list;
    }

    private String stringVal(Object o) {
        return o == null ? "" : o.toString();
    }

    private String normalizeText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.trim();
    }

    private record IntentSeed(String intentCode,
                              String level1,
                              String level2,
                              String nodeType,
                              String actionService,
                              List<String> samples) {
    }
}
