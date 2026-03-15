package org.buaa.rag.module.vector;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.buaa.rag.properties.MilvusProperties;
import org.springframework.stereotype.Component;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Milvus collection 管理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusCollectionManager {

    private final MilvusClientV2 milvusClient;
    private final MilvusProperties milvusProperties;

    public void ensureCollection(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("Milvus 向量维度必须大于 0");
        }
        if (collectionExists()) {
            return;
        }

        synchronized (this) {
            if (collectionExists()) {
                return;
            }
            createCollection(dimension);
        }
    }

    public boolean collectionExists() {
        return Boolean.TRUE.equals(
            milvusClient.hasCollection(
                HasCollectionReq.builder()
                    .collectionName(milvusProperties.getCollectionName())
                    .build()
            )
        );
    }

    private void createCollection(int dimension) {
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
            .collectionName(milvusProperties.getCollectionName())
            .collectionSchema(schema)
            .primaryFieldName("id")
            .vectorFieldName("embedding")
            .metricType(metricType.name())
            .consistencyLevel(ConsistencyLevel.BOUNDED)
            .indexParams(List.of(indexParam))
            .description("AI School Counselor vector store")
            .build();

        try {
            milvusClient.createCollection(request);
            log.info("创建 Milvus collection 成功: {}, dimension={}",
                milvusProperties.getCollectionName(), dimension);
        } catch (Exception e) {
            if (collectionExists() || isAlreadyExists(e)) {
                log.info("Milvus collection 已存在，跳过创建: {}", milvusProperties.getCollectionName());
                return;
            }
            throw e;
        }
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
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("already exist")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
