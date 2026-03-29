package org.buaa.rag.core.offline.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
 * 负责 chunk、ES 稀疏索引与 Milvus 向量的统一写入与清理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentArtifactService {

    private final ChunkMapper chunkMapper;
    private final EsIndexService esIndexService;
    private final EmbeddingService documentEmbeddingService;
    private final MilvusVectorStoreService milvusVectorStoreService;

    public void replace(DocumentDO document, List<ContentFragment> fragments) {
        if (document == null || fragments == null || fragments.isEmpty()) {
            return;
        }
        // 写入顺序：chunk -> ES -> embedding/Milvus。失败由上游工作流统一 cleanup。
        replaceChunks(document.getId(), fragments);
        esIndexService.index(document, fragments);
        List<float[]> vectors = documentEmbeddingService.encodeFragments(fragments);
        milvusVectorStoreService.upsertDocument(document, fragments, vectors);
    }

    private void replaceChunks(Long documentId, List<ContentFragment> fragments) {
        // 先逻辑删除旧分片，再插入新分片，避免历史脏分片参与召回。
        deleteChunks(documentId);
        for (ContentFragment fragment : fragments) {
            ChunkDO chunk = new ChunkDO();
            chunk.setDocumentId(documentId);
            chunk.setFragmentIndex(fragment.getFragmentId());
            chunk.setTextData(fragment.getTextContent());
            String normalizedText = fragment.getTextContent() == null ? "" : fragment.getTextContent();
            chunk.setMd5Hash(DigestUtils.md5Hex(normalizedText.getBytes(StandardCharsets.UTF_8)));
            int codePointCount = normalizedText.codePointCount(0, normalizedText.length());
            // 估算 token 用于后续控制上下文拼接体量（粗粒度估算即可）。
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

    public void cleanup(DocumentDO document) {
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
            milvusVectorStoreService.deleteByDocumentMd5(documentMd5);
        } catch (Exception e) {
            log.warn("回滚 Milvus 向量失败: {}", documentMd5, e);
        }
        try {
            deleteChunks(documentId);
        } catch (Exception e) {
            log.warn("回滚 chunk 失败: documentId={}", documentId, e);
        }
    }
}
