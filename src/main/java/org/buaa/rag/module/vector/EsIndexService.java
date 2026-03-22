package org.buaa.rag.module.vector;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.buaa.rag.dao.entity.ESIndexDO;
import org.buaa.rag.dto.ContentFragment;
import org.buaa.rag.properties.EsProperties;
import org.buaa.rag.tool.VectorEncoding;
import org.springframework.beans.factory.annotation.Value;
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
 * 职责：文本片段稀疏索引写入、向量编码以及 ES 文档删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsIndexService {

    private final VectorEncoding encodingService;
    private final ElasticsearchClient esClient;
    private final EsProperties esProperties;

    @Value("${rag.embedding.batch-size:10}")
    private int embeddingBatchSize;

    @Value("${rag.embedding.max-retries:2}")
    private int embeddingMaxRetries;

    @Value("${rag.embedding.retry-backoff-ms:400}")
    private long embeddingRetryBackoffMs;

    // ─────────────────── 公共接口 ───────────────────

    public void index(String documentMd5, List<ContentFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            log.warn("未发现文本片段，跳过索引: {}", documentMd5);
            return;
        }
        log.info("启动 ES 文本索引: {}, 片段数: {}", documentMd5, fragments.size());
        List<ESIndexDO> docs = buildIndexDocs(documentMd5, fragments);
        bulkIndex(docs);
        log.info("ES 文本索引完成: {}, 片段数: {}", documentMd5, fragments.size());
    }

    public void index(String documentMd5,
                      List<ContentFragment> fragments,
                      List<float[]> ignoredVectors) {
        index(documentMd5, fragments);
    }

    public List<float[]> encodeFragments(List<ContentFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }
        List<String> texts = fragments.stream()
            .map(ContentFragment::getTextContent)
            .collect(Collectors.toList());
        return encodeWithRetry(texts);
    }

    public void removeIndex(String documentMd5) {
        try {
            DeleteByQueryResponse resp = esClient.deleteByQuery(b -> b
                .index(esProperties.getIndex())
                .query(q -> q.term(t -> t.field("sourceMd5").value(documentMd5)))
                .refresh(true)
            );
            log.info("索引删除完成: {}, 删除数: {}", documentMd5, resp.deleted());
        } catch (Exception e) {
            log.error("索引删除失败: {}", documentMd5, e);
        }
    }

    // ─────────────────── 向量编码（带重试） ───────────────────

    private List<float[]> encodeWithRetry(List<String> texts) {
        int batchSize = Math.max(1, embeddingBatchSize);
        List<float[]> allVectors = new ArrayList<>(texts.size());

        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(start + batchSize, texts.size()));
            int batchNo = start / batchSize + 1;
            allVectors.addAll(encodeBatch(batch, batchNo));
        }

        if (allVectors.size() != texts.size()) {
            throw new RuntimeException("向量数量与文本片段数量不一致");
        }
        return allVectors;
    }

    private List<float[]> encodeBatch(List<String> texts, int batchNo) {
        int maxAttempts = Math.max(1, embeddingMaxRetries + 1);
        RuntimeException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                List<float[]> vectors = encodingService.encode(texts);
                validateVectors(vectors, texts.size());
                return vectors;
            } catch (Exception e) {
                last = new RuntimeException(
                    String.format("第 %d 批向量化失败（尝试 %d/%d）", batchNo, attempt, maxAttempts), e);
                if (attempt < maxAttempts) {
                    backoff(attempt);
                }
            }
        }
        throw last;
    }

    private void validateVectors(List<float[]> vectors, int expected) {
        if (vectors == null || vectors.size() != expected) {
            throw new RuntimeException("embedding 返回向量数量异常");
        }
        for (float[] v : vectors) {
            if (v == null || v.length == 0) {
                throw new RuntimeException("embedding 返回空向量");
            }
        }
    }

    private void backoff(int attempt) {
        long base = Math.max(50L, embeddingRetryBackoffMs);
        long delay = base * (1L << (attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("向量化重试被中断", e);
        }
    }

    // ─────────────────── ES 批量写入 ───────────────────

    private void bulkIndex(List<ESIndexDO> docs) {
        try {
            List<BulkOperation> ops = docs.stream()
                .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                    .index(esProperties.getIndex()).id(doc.getDocumentId()).document(doc))))
                .collect(Collectors.toList());

            BulkResponse resp = esClient.bulk(BulkRequest.of(b -> b.operations(ops)));

            if (resp.errors()) {
                logBulkErrors(resp);
                throw new RuntimeException("部分文档索引失败");
            }
            log.info("批量索引成功，文档数: {}", docs.size());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("索引操作失败", e);
        }
    }

    private void logBulkErrors(BulkResponse resp) {
        for (BulkResponseItem item : resp.items()) {
            if (item.error() != null) {
                log.error("索引失败 — 文档ID: {}, 原因: {}", item.id(), item.error().reason());
            }
        }
    }

    // ─────────────────── 构建索引文档 ───────────────────

    private List<ESIndexDO> buildIndexDocs(String md5,
                                           List<ContentFragment> fragments) {
        return IntStream.range(0, fragments.size())
            .mapToObj(index -> {
                ContentFragment fragment = fragments.get(index);
                return ESIndexDO.builder()
                    .documentId(md5 + "_" + fragment.getFragmentId())
                    .sourceMd5(md5)
                    .segmentNumber(fragment.getFragmentId())
                    .textPayload(fragment.getTextContent())
                    .build();
            })
            .collect(Collectors.toList());
    }
}
