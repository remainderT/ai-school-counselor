package org.buaa.rag.module.vector;

import java.util.ArrayList;
import java.util.List;

import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.SEARCH_SERVICE_ERROR;

import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.dto.ContentFragment;
import org.buaa.rag.properties.MilvusProperties;
import org.springframework.stereotype.Service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Milvus 向量写入服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorStoreService {

    private final MilvusClientV2 milvusClient;
    private final MilvusCollectionManager collectionManager;
    private final MilvusProperties milvusProperties;

    public void upsertDocument(DocumentDO document,
                               List<ContentFragment> fragments,
                               List<float[]> vectors) {
        if (document == null || fragments == null || fragments.isEmpty()) {
            return;
        }
        validateVectors(fragments, vectors);
        collectionManager.ensureCollection(vectors.get(0).length);

        List<JsonObject> rows = new ArrayList<>(fragments.size());
        for (int i = 0; i < fragments.size(); i++) {
            ContentFragment fragment = fragments.get(i);
            JsonObject row = new JsonObject();
            row.addProperty("id", buildPrimaryKey(document.getMd5Hash(), fragment.getFragmentId()));
            row.addProperty("source_md5", document.getMd5Hash());
            row.addProperty("segment_number", fragment.getFragmentId());
            if (document.getUserId() != null) {
                row.addProperty("user_id", document.getUserId());
            }
            if (document.getKnowledgeId() != null) {
                row.addProperty("knowledge_id", document.getKnowledgeId());
            }
            row.addProperty("text_payload", truncateText(fragment.getTextContent()));
            row.add("embedding", toJsonArray(vectors.get(i)));
            rows.add(row);
        }

        UpsertReq request = UpsertReq.builder()
            .collectionName(milvusProperties.getCollectionName())
            .data(rows)
            .build();

        UpsertResp response = milvusClient.upsert(request);
        log.info("Milvus 文档向量写入成功: collection={}, md5={}, upsertCnt={}",
            milvusProperties.getCollectionName(), document.getMd5Hash(), response.getUpsertCnt());
    }

    public void deleteByDocumentMd5(String documentMd5) {
        if (documentMd5 == null || documentMd5.isBlank()) {
            return;
        }
        if (!collectionManager.collectionExists()) {
            throw new ServiceException("Milvus collection 不存在: " + milvusProperties.getCollectionName(),
                SEARCH_SERVICE_ERROR);
        }
        try {
            DeleteResp response = milvusClient.delete(
                DeleteReq.builder()
                    .collectionName(milvusProperties.getCollectionName())
                    .filter("source_md5 == \"" + escapeLiteral(documentMd5.trim()) + "\"")
                    .build()
            );
            log.info("Milvus 删除文档向量完成: collection={}, md5={}, deleteCnt={}",
                milvusProperties.getCollectionName(), documentMd5, response.getDeleteCnt());
        } catch (Exception e) {
            throw new ServiceException("Milvus 删除文档向量失败: " + e.getMessage(), e, SEARCH_SERVICE_ERROR);
        }
    }

    private void validateVectors(List<ContentFragment> fragments, List<float[]> vectors) {
        if (vectors == null || fragments.size() != vectors.size()) {
            throw new IllegalArgumentException("Milvus 写入时文本片段与向量数量不一致");
        }
        for (float[] vector : vectors) {
            if (vector == null || vector.length == 0) {
                throw new IllegalArgumentException("Milvus 写入时向量不能为空");
            }
        }
    }

    private String buildPrimaryKey(String documentMd5, int fragmentId) {
        return documentMd5 + "_" + fragmentId;
    }

    private String truncateText(String text) {
        String value = text == null ? "" : text;
        int maxLength = Math.max(1, milvusProperties.getMaxTextLength());
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private JsonArray toJsonArray(float[] vector) {
        JsonArray array = new JsonArray(vector.length);
        for (float value : vector) {
            array.add(value);
        }
        return array;
    }

    private String escapeLiteral(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
