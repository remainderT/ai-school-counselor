package org.buaa.rag.core.online.retrieval;

import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.SEARCH_SERVICE_ERROR;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.offline.index.MilvusCollectionManager;
import org.buaa.rag.properties.MilvusProperties;
import org.springframework.stereotype.Service;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Milvus 向量检索服务。
 * <p>
 * 每次检索均需显式传入 collectionName（= knowledge.name），实现知识库级别的检索隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusRetrieverService {

    private static final int MIN_EF = 8;
    private static final int MAX_EF = 32768;

    private final MilvusClientV2 milvusClient;
    private final MilvusCollectionManager collectionManager;
    private final MilvusProperties milvusProperties;

    /**
     * 在指定 Collection 中执行向量相似度检索。
     *
     * @param collectionName 知识库 name（= bucket_name）
     * @param queryVector    查询向量
     * @param topK           返回最大结果数
     */
    public List<RetrievalMatch> search(String collectionName, List<Float> queryVector, int topK) {
        if (queryVector == null || queryVector.isEmpty() || topK <= 0) {
            return Collections.emptyList();
        }
        if (!collectionManager.collectionExists(collectionName)) {
            log.warn("Milvus collection 不存在，跳过检索: collection={}", collectionName);
            return Collections.emptyList();
        }

        try {
            int requestTopK = Math.max(1, topK);
            int baseEf = Math.max(MIN_EF, milvusProperties.getSearchEf());
            int desiredEf = Math.max(baseEf, requestTopK + 1);
            int effectiveEf = Math.min(MAX_EF, desiredEf);
            int effectiveTopK = requestTopK;
            if (effectiveTopK >= effectiveEf) {
                effectiveTopK = Math.max(1, effectiveEf - 1);
                log.warn("Milvus 检索参数已自动修正: collection={}, requestedTopK={}, effectiveTopK={}, effectiveEf={}",
                    collectionName, requestTopK, effectiveTopK, effectiveEf);
            }

            float[] vector = l2Normalize(toFloatArray(queryVector));
            List<BaseVector> vectors = List.of(new FloatVec(vector));
            Map<String, Object> searchParams = Map.of(
                "metric_type", milvusProperties.getMetricType(),
                "ef", effectiveEf
            );

            SearchReq request = SearchReq.builder()
                .collectionName(collectionName)
                .annsField("embedding")
                .data(vectors)
                .topK(effectiveTopK)
                .searchParams(searchParams)
                .outputFields(List.of("source_md5", "segment_number", "text_payload"))
                .build();

            SearchResp response = milvusClient.search(request);
            List<List<SearchResp.SearchResult>> results = response.getSearchResults();
            if (results == null || results.isEmpty()) {
                return Collections.emptyList();
            }
            return results.get(0).stream()
                .map(result -> new RetrievalMatch(
                    Objects.toString(result.getEntity().get("source_md5"), ""),
                    parseInteger(result.getEntity().get("segment_number")),
                    Objects.toString(result.getEntity().get("text_payload"), ""),
                    (double) result.getScore()
                ))
                .filter(match -> match.getFileMd5() != null && !match.getFileMd5().isBlank())
                .collect(Collectors.toList());
        } catch (Exception e) {
            throw new ServiceException("Milvus 向量检索失败: " + e.getMessage(), e, SEARCH_SERVICE_ERROR);
        }
    }

    private float[] toFloatArray(List<Float> src) {
        float[] dst = new float[src.size()];
        for (int idx = 0; idx < src.size(); idx++) {
            dst[idx] = src.get(idx);
        }
        return dst;
    }

    private float[] l2Normalize(float[] vector) {
        double squaredSum = 0.0;
        for (float v : vector) {
            squaredSum += (double) v * v;
        }
        double magnitude = Math.sqrt(squaredSum);
        if (magnitude <= 1e-12) {
            return vector;
        }
        float[] unit = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            unit[i] = (float) (vector[i] / magnitude);
        }
        return unit;
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
