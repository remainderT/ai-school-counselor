package org.buaa.rag.core.offline.index;

import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.SEARCH_SERVICE_ERROR;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.core.model.RetrievalMatch;
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
 * Milvus 向量检索服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusRetrieverService {

    private final MilvusClientV2 milvusClient;
    private final MilvusCollectionManager collectionManager;
    private final MilvusProperties milvusProperties;

    public List<RetrievalMatch> search(List<Float> queryVector, int topK) {
        if (queryVector == null || queryVector.isEmpty() || topK <= 0) {
            return Collections.emptyList();
        }
        collectionManager.ensureReady();

        try {
            float[] vector = normalize(toArray(queryVector));
            List<BaseVector> vectors = List.of(new FloatVec(vector));
            Map<String, Object> searchParams = Map.of(
                "metric_type", milvusProperties.getMetricType(),
                "ef", Math.max(8, milvusProperties.getSearchEf())
            );

            SearchReq request = SearchReq.builder()
                .collectionName(milvusProperties.getCollectionName())
                .annsField("embedding")
                .data(vectors)
                .topK(topK)
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

    private float[] toArray(List<Float> values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i);
        }
        return vector;
    }

    private float[] normalize(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm <= 0) {
            return vector;
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
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
