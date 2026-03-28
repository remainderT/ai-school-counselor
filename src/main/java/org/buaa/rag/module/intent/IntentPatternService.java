package org.buaa.rag.module.intent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.buaa.rag.dao.entity.IntentPatternSeedDO;
import org.buaa.rag.dao.mapper.IntentPatternSeedMapper;
import org.buaa.rag.dto.IntentDecision;
import org.buaa.rag.module.index.VectorEncoding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentPatternService {

    private final ElasticsearchClient esClient;
    private final VectorEncoding vectorEncoding;
    private final IntentPatternSeedMapper intentPatternSeedMapper;

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
            if (score < 0.7) {
                return Optional.empty();
            }

            Map<String, Object> source = hit.source();
            if (source == null) {
                return Optional.empty();
            }
            String level1 = stringVal(source.get("level1"));
            String level2 = stringVal(source.get("level2"));
            String code = stringVal(source.get("intentCode"));

            IntentDecision.Action action = switch (code) {
                case "guide:leave" -> IntentDecision.Action.ROUTE_TOOL;
                case "guide:repair" -> IntentDecision.Action.ROUTE_TOOL;
                case "guide:score" -> IntentDecision.Action.ROUTE_TOOL;
                case "life:weather" -> IntentDecision.Action.ROUTE_TOOL;
                case "life:crisis" -> IntentDecision.Action.CRISIS;
                default -> IntentDecision.Action.ROUTE_RAG;
            };

            String toolName = switch (code) {
                case "guide:leave" -> "leave";
                case "guide:repair" -> "repair";
                case "guide:score" -> "score";
                case "life:weather" -> "weather";
                default -> null;
            };

            IntentDecision.IntentDecisionBuilder builder = IntentDecision.builder()
                    .level1(level1)
                    .level2(level2)
                    .confidence(score)
                    .toolName(toolName)
                    .action(action);

            if (action == IntentDecision.Action.CRISIS) {
                builder
                        .clarifyQuestion("如需帮助请立即联系辅导员，是否需要电话？")
                        .strategy(IntentDecision.Strategy.CLARIFY_ONLY);
            }

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
        // create index first
        esClient.indices().create(CreateIndexRequest.of(c -> c.index(intentIndex)));
        // put mapping with dense_vector
        esClient.indices().putMapping(PutMappingRequest.of(m -> m
                .index(intentIndex)
                .properties("intentCode", Property.of(p -> p.keyword(k -> k)))
                .properties("level1", Property.of(p -> p.keyword(k -> k)))
                .properties("level2", Property.of(p -> p.keyword(k -> k)))
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
        List<String> samples = seedPatterns.stream()
                .flatMap(seed -> seed.samples().stream())
                .toList();
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
        // 以一次编码维度为准
        List<float[]> encoded = vectorEncoding.encode(List.of("probe"));
        if (encoded.isEmpty()) {
            return 0;
        }
        float[] v = encoded.get(0);
        return v.length;
    }

    private void loadSeedsIfNeeded() {
        if (seedPatterns != null) {
            return;
        }
        List<IntentPatternSeedDO> rows = intentPatternSeedMapper.selectList(
                Wrappers.lambdaQuery(IntentPatternSeedDO.class)
                        .eq(IntentPatternSeedDO::getDelFlag, 0)
                        .eq(IntentPatternSeedDO::getEnabled, 1)
                        .orderByAsc(IntentPatternSeedDO::getSortOrder, IntentPatternSeedDO::getId)
        );
        if (rows == null || rows.isEmpty()) {
            seedPatterns = List.of();
            log.warn("数据库中未找到有效意图样本种子（intent_pattern_seed）");
            return;
        }
        Map<String, IntentSeedBuilder> grouped = new LinkedHashMap<>();
        for (IntentPatternSeedDO row : rows) {
            if (row == null
                    || !StringUtils.hasText(row.getIntentCode())
                    || !StringUtils.hasText(row.getSample())) {
                continue;
            }
            String intentCode = row.getIntentCode().trim();
            String level1 = row.getLevel1() == null ? "" : row.getLevel1().trim();
            String level2 = row.getLevel2() == null ? "" : row.getLevel2().trim();
            String key = intentCode + "||" + level1 + "||" + level2;
            IntentSeedBuilder builder = grouped.computeIfAbsent(
                    key, ignored -> new IntentSeedBuilder(intentCode, level1, level2)
            );
            builder.samples().add(row.getSample().trim());
        }
        List<IntentSeed> loaded = grouped.values().stream()
                .map(IntentSeedBuilder::build)
                .filter(seed -> !seed.samples().isEmpty())
                .toList();
        seedPatterns = Collections.unmodifiableList(loaded);
        log.info("从数据库加载意图样本种子成功，意图数: {}, 样本数: {}",
                seedPatterns.size(),
                seedPatterns.stream().mapToInt(item -> item.samples().size()).sum());
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

    private record IntentSeed(String intentCode, String level1, String level2, List<String> samples) {
    }

    private record IntentSeedBuilder(String intentCode, String level1, String level2, List<String> samples) {

        private IntentSeedBuilder(String intentCode, String level1, String level2) {
            this(intentCode, level1, level2, new ArrayList<>());
        }

        private IntentSeed build() {
            return new IntentSeed(intentCode, level1, level2, List.copyOf(samples));
        }
    }
}
