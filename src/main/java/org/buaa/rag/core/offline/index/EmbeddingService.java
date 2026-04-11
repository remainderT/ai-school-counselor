package org.buaa.rag.core.offline.index;

import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.EMBEDDING_SERVICE_ERROR;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.core.model.ContentFragment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档向量化服务
 * <p>
 * 负责离线文档分片的批量向量化，支持多批并行调用以降低整体耗时。
 */
@Slf4j
@Service
public class EmbeddingService {

    private final VectorEncoding encodingService;
    private final ExecutorService embeddingPool;
    private final int concurrency;

    @Value("${rag.embedding.batch-size:10}")
    private int batchSize;

    @Value("${rag.embedding.max-retries:2}")
    private int maxRetries;

    @Value("${rag.embedding.retry-backoff-ms:400}")
    private long retryBackoffMs;

    public EmbeddingService(VectorEncoding encodingService,
                            @Value("${rag.embedding.concurrency:4}") int concurrency) {
        this.encodingService = encodingService;
        this.concurrency = Math.max(1, concurrency);
        AtomicInteger counter = new AtomicInteger(0);
        this.embeddingPool = Executors.newFixedThreadPool(this.concurrency, r -> {
            Thread t = new Thread(r, "embed-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        embeddingPool.shutdownNow();
    }

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

        log.info("开始向量编码，共 {} 个文本，分 {} 批处理（每批 {}），并行度: {}",
            texts.size(), totalBatches, resolvedBatchSize, concurrency);

        // 构建所有批次
        List<List<String>> batches = new ArrayList<>(totalBatches);
        for (int start = 0; start < texts.size(); start += resolvedBatchSize) {
            batches.add(texts.subList(start, Math.min(start + resolvedBatchSize, texts.size())));
        }

        // 并行提交所有批次，由线程池的固定线程数控制实际并发度
        List<CompletableFuture<List<float[]>>> futures = new ArrayList<>(totalBatches);
        for (int i = 0; i < batches.size(); i++) {
            final int batchNo = i + 1;
            final List<String> batch = batches.get(i);
            futures.add(CompletableFuture.supplyAsync(
                () -> encodeBatch(batch, batchNo), embeddingPool));
        }

        // 按原始顺序收集结果，保证向量与文本片段一一对应
        List<float[]> allVectors = new ArrayList<>(texts.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                allVectors.addAll(futures.get(i).join());
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof ServiceException se) {
                    throw se;
                }
                throw new ServiceException("向量编码失败", cause, EMBEDDING_SERVICE_ERROR);
            }
            int batchNo = i + 1;
            if (batchNo % 3 == 0 || batchNo == totalBatches) {
                log.info("向量编码进度: {}/{}批完成，已生成 {} 个向量",
                    batchNo, totalBatches, allVectors.size());
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
