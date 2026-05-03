package org.buaa.rag.core.offline.index;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.buaa.rag.core.model.ContentFragment;
import org.buaa.rag.properties.MilvusProperties;
import org.springframework.stereotype.Component;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Milvus Collection 按需管理器。
 * <p>
 * 每个知识库对应一个独立的 Collection（名称 = knowledge.name），
 * 在知识库创建/删除时由 KnowledgeServiceImpl 调用本类完成生命周期管理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusCollectionManager {

    private final MilvusClientV2 milvusClient;
    private final MilvusProperties milvusProperties;
    private final EmbeddingService embeddingService;

    /**
     * 确保指定 Collection 存在，不存在则创建。
     *
     * @param collectionName 知识库 name（即 bucket_name）
     */
    public void ensureCollection(String collectionName) {
        if (collectionExists(collectionName)) {
            log.info("Milvus collection 已存在，跳过创建: {}", collectionName);
            return;
        }
        int dimension = detectEmbeddingDimension();
        createCollection(collectionName, dimension);
    }

    /**
     * 删除指定 Collection（知识库删除时调用）。
     *
     * @param collectionName 知识库 name
     */
    public void dropCollection(String collectionName) {
        if (!collectionExists(collectionName)) {
            log.warn("Milvus collection 不存在，跳过删除: {}", collectionName);
            return;
        }
        try {
            milvusClient.dropCollection(DropCollectionReq.builder()
                .collectionName(collectionName)
                .build());
            log.info("Milvus collection 已删除: {}", collectionName);
        } catch (Exception e) {
            log.error("Milvus collection 删除失败: {}", collectionName, e);
            throw e;
        }
    }

    public boolean collectionExists(String collectionName) {
        return Boolean.TRUE.equals(
            milvusClient.hasCollection(
                HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build()
            )
        );
    }

    private void createCollection(String collectionName, int dimension) {
        List<CreateCollectionReq.FieldSchema> fields = List.of(
            CreateCollectionReq.FieldSchema.builder()
                .name("id")
                .dataType(DataType.VarChar)
                .maxLength(96)
                .isPrimaryKey(true)
                .autoID(false)
                .build(),
            CreateCollectionReq.FieldSchema.builder()
                .name("source_md5")
                .dataType(DataType.VarChar)
                .maxLength(64)
                .build(),
            CreateCollectionReq.FieldSchema.builder()
                .name("segment_number")
                .dataType(DataType.Int64)
                .build(),
            CreateCollectionReq.FieldSchema.builder()
                .name("user_id")
                .dataType(DataType.Int64)
                .build(),
            CreateCollectionReq.FieldSchema.builder()
                .name("knowledge_id")
                .dataType(DataType.Int64)
                .build(),
            CreateCollectionReq.FieldSchema.builder()
                .name("text_payload")
                .dataType(DataType.VarChar)
                .maxLength(milvusProperties.getMaxTextLength())
                .build(),
            CreateCollectionReq.FieldSchema.builder()
                .name("embedding")
                .dataType(DataType.FloatVector)
                .dimension(dimension)
                .build()
        );

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
            .fieldSchemaList(fields)
            .build();

        IndexParam.MetricType metricType = resolveMetricType();
        IndexParam indexParam = IndexParam.builder()
            .fieldName("embedding")
            .indexName("embedding")
            .indexType(IndexParam.IndexType.HNSW)
            .metricType(metricType)
            .extraParams(Map.of(
                "M", String.valueOf(Math.max(4, milvusProperties.getIndexM())),
                "efConstruction", String.valueOf(Math.max(8, milvusProperties.getEfConstruction())),
                "mmap.enabled", "false"
            ))
            .build();

        CreateCollectionReq request = CreateCollectionReq.builder()
            .collectionName(collectionName)
            .collectionSchema(schema)
            .primaryFieldName("id")
            .vectorFieldName("embedding")
            .metricType(metricType.name())
            .consistencyLevel(ConsistencyLevel.BOUNDED)
            .indexParams(List.of(indexParam))
            .description("AI School Counselor vector store - " + collectionName)
            .build();

        try {
            milvusClient.createCollection(request);
            log.info("创建 Milvus collection 成功: {}, dimension={}", collectionName, dimension);
        } catch (Exception e) {
            if (collectionExists(collectionName) || isAlreadyExists(e)) {
                log.info("Milvus collection 已存在，跳过创建: {}", collectionName);
                return;
            }
            throw e;
        }
    }

    private int detectEmbeddingDimension() {
        List<float[]> vectors = embeddingService.encodeFragments(
            List.of(new ContentFragment(1, "milvus-init-probe"))
        );
        if (vectors == null || vectors.isEmpty() || vectors.get(0) == null || vectors.get(0).length <= 0) {
            throw new IllegalStateException("无法探测 embedding 向量维度，Milvus 初始化失败");
        }
        int dimension = vectors.get(0).length;
        log.info("Milvus 探测到 embedding 维度: {}", dimension);
        return dimension;
    }

    private IndexParam.MetricType resolveMetricType() {
        String value = milvusProperties.getMetricType();
        if (value == null || value.isBlank()) {
            return IndexParam.MetricType.COSINE;
        }
        return IndexParam.MetricType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private boolean isAlreadyExists(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("already exist")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
