package org.buaa.rag.core.offline.index;

import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.EMBEDDING_SERVICE_ERROR;

import java.util.ArrayList;
import java.util.List;

import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.core.model.ContentFragment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档向量化服务
 * <p>
 * 负责离线文档分片的批量向量化与重试控制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final VectorEncoding encodingService;

    @Value("${rag.embedding.batch-size:10}")
    private int batchSize;

    @Value("${rag.embedding.max-retries:2}")
    private int maxRetries;

    @Value("${rag.embedding.retry-backoff-ms:400}")
    private long retryBackoffMs;

    public List<float[]> encodeFragments(List<ContentFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }

        List<String> texts = fragments.stream()
            .map(ContentFragment::getTextContent)
            .toList();

        long startTime = System.currentTimeMillis();
        List<float[]> vectors = encodeTexts(texts);
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("文档向量化完成，片段数: {}，耗时: {}ms", vectors.size(), elapsed);
        return vectors;
    }

    private List<float[]> encodeTexts(List<String> texts) {
        int resolvedBatchSize = Math.max(1, batchSize);
        int totalBatches = (int) Math.ceil((double) texts.size() / resolvedBatchSize);
        List<float[]> allVectors = new ArrayList<>(texts.size());

        log.info("开始向量编码，共 {} 个文本，分 {} 批处理（每批 {}）", texts.size(), totalBatches, resolvedBatchSize);

        // 按批次调用 embedding，降低单次请求体积与超时风险。
        for (int start = 0; start < texts.size(); start += resolvedBatchSize) {
            List<String> batch = texts.subList(start, Math.min(start + resolvedBatchSize, texts.size()));
            int batchNo = start / resolvedBatchSize + 1;
            allVectors.addAll(encodeBatch(batch, batchNo));

            // 每5批或最后一批打印一次进度
            if (batchNo % 5 == 0 || batchNo == totalBatches) {
                log.info("向量编码进度: {}/{}批完成，已生成 {} 个向量", batchNo, totalBatches, allVectors.size());
            }
        }

        if (allVectors.size() != texts.size()) {
            throw new ServiceException("向量数量与文本片段数量不一致", EMBEDDING_SERVICE_ERROR);
        }
        return allVectors;
    }

    private List<float[]> encodeBatch(List<String> texts, int batchNo) {
        int totalAttempts = Math.max(1, maxRetries + 1);
        ServiceException last = null;

        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            try {
                List<float[]> vectors = encodingService.encode(texts);
                validateVectors(vectors, texts.size());
                return vectors;
            } catch (Exception e) {
                // 每批独立重试，避免单批异常影响已完成批次结果。
                last = new ServiceException(
                    String.format("第 %d 批文档向量化失败（尝试 %d/%d）", batchNo, attempt, totalAttempts),
                    e,
                    EMBEDDING_SERVICE_ERROR
                );
                if (attempt < totalAttempts) {
                    backoff(attempt);
                }
            }
        }
        throw last;
    }

    private void validateVectors(List<float[]> vectors, int expectedSize) {
        if (vectors == null || vectors.size() != expectedSize) {
            throw new ServiceException("embedding 返回向量数量异常", EMBEDDING_SERVICE_ERROR);
        }
        for (float[] vector : vectors) {
            if (vector == null || vector.length == 0) {
                throw new ServiceException("embedding 返回空向量", EMBEDDING_SERVICE_ERROR);
            }
        }
    }

    private void backoff(int attempt) {
        long baseDelay = Math.max(50L, retryBackoffMs);
        long delay = baseDelay * (1L << (attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("向量化重试被中断", e, EMBEDDING_SERVICE_ERROR);
        }
    }
}
