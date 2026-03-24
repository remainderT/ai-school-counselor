package org.buaa.rag.module.index;

import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.SEARCH_SERVICE_ERROR;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.dao.entity.ESIndexDO;
import org.buaa.rag.dto.ContentFragment;
import org.buaa.rag.properties.EsProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

    public void index(String documentMd5, List<ContentFragment> fragments) {
        if (!StringUtils.hasText(documentMd5)) {
            throw new ServiceException("文档 md5 不能为空", SEARCH_SERVICE_ERROR);
        }
        if (fragments == null || fragments.isEmpty()) {
            log.warn("未发现文本片段，跳过索引: {}", documentMd5);
            return;
        }

        log.info("启动 ES 文本索引: {}, 片段数: {}", documentMd5, fragments.size());
        bulkIndex(buildIndexDocs(documentMd5, fragments));
        log.info("ES 文本索引完成: {}, 片段数: {}", documentMd5, fragments.size());
    }

    public void deleteByDocumentMd5(String documentMd5) {
        if (!StringUtils.hasText(documentMd5)) {
            return;
        }
        try {
            DeleteByQueryResponse response = esClient.deleteByQuery(b -> b
                .index(esProperties.getIndex())
                .query(q -> q.term(t -> t.field("sourceMd5").value(documentMd5)))
                .refresh(true)
            );
            log.info("ES 文本索引删除完成: {}, 删除数: {}", documentMd5, response.deleted());
        } catch (Exception e) {
            throw new ServiceException("删除 ES 文本索引失败: " + e.getMessage(), e, SEARCH_SERVICE_ERROR);
        }
    }

    private void bulkIndex(List<ESIndexDO> docs) {
        try {
            List<BulkOperation> operations = docs.stream()
                .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                    .index(esProperties.getIndex())
                    .id(doc.getDocumentId())
                    .document(doc))))
                .collect(Collectors.toList());

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

    private List<ESIndexDO> buildIndexDocs(String documentMd5, List<ContentFragment> fragments) {
        return IntStream.range(0, fragments.size())
            .mapToObj(index -> {
                ContentFragment fragment = fragments.get(index);
                return ESIndexDO.builder()
                    .documentId(documentMd5 + "_" + fragment.getFragmentId())
                    .sourceMd5(documentMd5)
                    .segmentNumber(fragment.getFragmentId())
                    .textPayload(fragment.getTextContent())
                    .build();
            })
            .toList();
    }
}
