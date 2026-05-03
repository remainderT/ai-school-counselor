package org.buaa.rag.core.offline.index;


import static org.buaa.rag.common.enums.OnlineErrorCodeEnum.SEARCH_SERVICE_ERROR;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.ESIndexDO;
import org.buaa.rag.core.model.ContentFragment;
import org.buaa.rag.properties.EsProperties;
import org.springframework.stereotype.Service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档 ES 索引服务
 * <p>
 * 职责：文本片段稀疏索引写入与删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsIndexService {

    private final ElasticsearchClient esClient;
    private final EsProperties esProperties;

    public void index(DocumentDO document, List<ContentFragment> fragments) {
        if (document == null || document.getId() == null || document.getId() <= 0) {
            throw new ServiceException("文档 ID 不能为空", SEARCH_SERVICE_ERROR);
        }
        if (fragments == null || fragments.isEmpty()) {
            log.warn("未发现文本片段，跳过索引: documentId={}", document.getId());
            return;
        }

        log.info("启动 ES 文本索引: documentId={}, 片段数={}", document.getId(), fragments.size());
        // 同一文档分片使用稳定 ID，重复写入可覆盖，便于离线重试幂等。
        bulkIndex(buildIndexDocs(document, fragments));
        log.info("ES 文本索引完成: documentId={}, 片段数={}", document.getId(), fragments.size());
    }

    /**
     * 索引（upsert）单个 chunk 到 ES。
     *
     * @param documentId    文档 ID
     * @param fragmentIndex chunk 片段序号
     * @param textData      新的文本内容
     */
    public void indexSingleChunk(Long documentId, int fragmentIndex, String textData) {
        String esDocId = buildEsDocId(documentId, fragmentIndex);
        ESIndexDO doc = ESIndexDO.builder()
            .id(esDocId)
            .documentId(documentId)
            .fragmentIndex(fragmentIndex)
            .textData(textData)
            .build();
        bulkIndex(List.of(doc));
        log.info("ES 单 chunk 索引完成: documentId={}, fragmentIndex={}", documentId, fragmentIndex);
    }

    /**
     * 删除单个 chunk 的 ES 索引文档。
     *
     * @param documentId    文档 ID
     * @param fragmentIndex chunk 片段序号
     */
    public void deleteSingleChunk(Long documentId, int fragmentIndex) {
        String esDocId = buildEsDocId(documentId, fragmentIndex);
        try {
            esClient.delete(d -> d.index(esProperties.getIndex()).id(esDocId));
            log.info("ES 单 chunk 索引删除完成: documentId={}, fragmentIndex={}", documentId, fragmentIndex);
        } catch (Exception e) {
            throw new ServiceException("删除 ES 单 chunk 索引失败: " + e.getMessage(), e, SEARCH_SERVICE_ERROR);
        }
    }

    public void deleteByDocumentId(Long documentId) {
        if (documentId == null || documentId <= 0) {
            return;
        }
        try {
            DeleteByQueryResponse response = esClient.deleteByQuery(b -> b
                .index(esProperties.getIndex())
                .query(q -> q.term(t -> t.field("document_id").value(documentId)))
                .refresh(true)
            );
            log.info("ES 文本索引删除完成: documentId={}, 删除数={}", documentId, response.deleted());
        } catch (Exception e) {
            throw new ServiceException("删除 ES 文本索引失败: " + e.getMessage(), e, SEARCH_SERVICE_ERROR);
        }
    }

    private void bulkIndex(List<ESIndexDO> docs) {
        try {
            List<BulkOperation> operations = docs.stream()
                .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                    .index(esProperties.getIndex())
                    .id(doc.getId())
                    .document(doc))))
                .collect(Collectors.toList());

            // 批量写入提升吞吐，errors=true 时逐条记录失败项。
            BulkResponse response = esClient.bulk(BulkRequest.of(b -> b.operations(operations)));
            if (response.errors()) {
                logBulkErrors(response);
                throw new ServiceException("部分 ES 文本索引写入失败", SEARCH_SERVICE_ERROR);
            }

            log.info("ES 批量索引成功，文档数: {}", docs.size());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("ES 索引操作失败: " + e.getMessage(), e, SEARCH_SERVICE_ERROR);
        }
    }

    private void logBulkErrors(BulkResponse response) {
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                log.error("索引失败，文档ID: {}, 原因: {}", item.id(), item.error().reason());
            }
        }
    }

    private List<ESIndexDO> buildIndexDocs(DocumentDO document, List<ContentFragment> fragments) {
        return IntStream.range(0, fragments.size())
            .mapToObj(index -> {
                ContentFragment fragment = fragments.get(index);
                return ESIndexDO.builder()
                    .id(buildEsDocId(document.getId(), fragment.getFragmentId()))
                    .documentId(document.getId())
                    .fragmentIndex(fragment.getFragmentId())
                    .textData(fragment.getTextContent())
                    .build();
            })
            .toList();
    }

    private String buildEsDocId(Long documentId, Integer fragmentIndex) {
        return documentId + "_" + fragmentIndex;
    }
}
