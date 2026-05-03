package org.buaa.rag.core.offline.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.apache.commons.codec.digest.DigestUtils;
import org.buaa.rag.dao.entity.ChunkDO;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.mapper.ChunkMapper;
import org.buaa.rag.core.model.ContentFragment;
import org.buaa.rag.core.offline.index.EmbeddingService;
import org.buaa.rag.core.offline.index.EsIndexService;
import org.buaa.rag.core.offline.index.MilvusVectorStoreService;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档产物处理器
 * <p>
 * 负责 chunk、ES 稀疏索引与 Milvus 向量的统一写入与清理。
 * ES 索引与向量编码并行执行以降低整体耗时。
 * collectionName 由调用方透传，对应知识库的 Milvus Collection。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentArtifactService {

    private final ChunkMapper chunkMapper;
    private final EsIndexService esIndexService;
    private final EmbeddingService documentEmbeddingService;
    private final MilvusVectorStoreService milvusVectorStoreService;

    /**
     * @param collectionName 知识库对应的 Milvus Collection 名称
     * @param document       文档实体
     * @param fragments      文本片段列表
     */
    public void replace(String collectionName, DocumentDO document, List<ContentFragment> fragments) {
        if (document == null || fragments == null || fragments.isEmpty()) {
            return;
        }
        // 1. 先写 chunk
        replaceChunks(document.getId(), fragments);

        // 2. ES 索引和向量编码并行执行
        CompletableFuture<Void> esFuture = CompletableFuture.runAsync(
            () -> esIndexService.index(document, fragments));
        CompletableFuture<List<float[]>> embeddingFuture = CompletableFuture.supplyAsync(
            () -> documentEmbeddingService.encodeFragments(fragments));

        try {
            CompletableFuture.allOf(esFuture, embeddingFuture).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("产物写入异常", cause);
        }

        // 3. 向量写入 Milvus
        List<float[]> vectors = embeddingFuture.join();
        milvusVectorStoreService.upsertDocument(collectionName, document, fragments, vectors);
    }

    /**
     * 清理文档的所有产物（chunk、ES 索引、Milvus 向量）。
     *
     * @param collectionName 知识库对应的 Milvus Collection 名称
     * @param document       文档实体
     */
    public void cleanup(String collectionName, DocumentDO document) {
        if (document == null) {
            return;
        }
        Long documentId = document.getId();
        String documentMd5 = document.getMd5Hash();
        // cleanup 采用 best-effort，任何一步失败都不阻断后续清理动作。
        try {
            esIndexService.deleteByDocumentId(documentId);
        } catch (Exception e) {
            log.warn("回滚 ES 文本索引失败: documentId={}", documentId, e);
        }
        try {
            milvusVectorStoreService.deleteByDocumentMd5(collectionName, documentMd5);
        } catch (Exception e) {
            log.warn("回滚 Milvus 向量失败: collection={}, md5={}", collectionName, documentMd5, e);
        }
        try {
            deleteChunks(documentId);
        } catch (Exception e) {
            log.warn("回滚 chunk 失败: documentId={}", documentId, e);
        }
    }

    private void replaceChunks(Long documentId, List<ContentFragment> fragments) {
        deleteChunks(documentId);
        for (ContentFragment fragment : fragments) {
            ChunkDO chunk = new ChunkDO();
            chunk.setDocumentId(documentId);
            chunk.setFragmentIndex(fragment.getFragmentId());
            chunk.setTextData(fragment.getTextContent());
            String normalizedText = fragment.getTextContent() == null ? "" : fragment.getTextContent();
            chunk.setMd5Hash(DigestUtils.md5Hex(normalizedText.getBytes(StandardCharsets.UTF_8)));
            int codePointCount = normalizedText.codePointCount(0, normalizedText.length());
            chunk.setTokenEstimate(normalizedText.isBlank() ? 0 : Math.max(1, (int) Math.ceil(codePointCount / 1.8d)));
            chunkMapper.insert(chunk);
        }
        log.info("已保存 {} 个 chunk: documentId={}", fragments.size(), documentId);
    }

    private void deleteChunks(Long documentId) {
        if (documentId == null || documentId <= 0) {
            return;
        }
        chunkMapper.update(
            null,
            Wrappers.lambdaUpdate(ChunkDO.class)
                .eq(ChunkDO::getDocumentId, documentId)
                .eq(ChunkDO::getDelFlag, 0)
                .set(ChunkDO::getDelFlag, 1)
        );
        log.debug("已逻辑删除文档 chunk: documentId={}", documentId);
    }
}
