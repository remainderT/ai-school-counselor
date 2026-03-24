package org.buaa.rag.module.ingestion;

import java.util.List;

import org.buaa.rag.dao.entity.ChunkDO;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.mapper.ChunkMapper;
import org.buaa.rag.dto.ContentFragment;
import org.buaa.rag.module.index.DocumentEmbeddingService;
import org.buaa.rag.module.index.EsIndexService;
import org.buaa.rag.module.index.MilvusVectorStoreService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
    private final DocumentEmbeddingService documentEmbeddingService;
    private final MilvusVectorStoreService milvusVectorStoreService;

    public void replace(DocumentDO document, List<ContentFragment> fragments) {
        if (document == null || fragments == null || fragments.isEmpty()) {
            return;
        }
        replaceChunks(document.getMd5Hash(), fragments);
        esIndexService.index(document.getMd5Hash(), fragments);
        List<float[]> vectors = documentEmbeddingService.encodeFragments(fragments);
        milvusVectorStoreService.upsertDocument(document, fragments, vectors);
    }

    private void replaceChunks(String documentMd5, List<ContentFragment> fragments) {
        deleteChunks(documentMd5);
        for (ContentFragment fragment : fragments) {
            ChunkDO chunk = new ChunkDO();
            chunk.setDocumentMd5(documentMd5);
            chunk.setFragmentIndex(fragment.getFragmentId());
            chunk.setTextData(fragment.getTextContent());
            chunkMapper.insert(chunk);
        }
        log.info("已保存 {} 个 chunk", fragments.size());
    }

    private void deleteChunks(String documentMd5) {
        if (!StringUtils.hasText(documentMd5)) {
            return;
        }
        chunkMapper.delete(
            Wrappers.lambdaQuery(ChunkDO.class)
                .eq(ChunkDO::getDocumentMd5, documentMd5)
        );
        log.debug("已删除文档 chunk: {}", documentMd5);
    }

    public void cleanup(String documentMd5) {
        if (!StringUtils.hasText(documentMd5)) {
            return;
        }
        try {
            esIndexService.deleteByDocumentMd5(documentMd5);
        } catch (Exception e) {
            log.warn("回滚 ES 文本索引失败: {}", documentMd5, e);
        }
        try {
            milvusVectorStoreService.deleteByDocumentMd5(documentMd5);
        } catch (Exception e) {
            log.warn("回滚 Milvus 向量失败: {}", documentMd5, e);
        }
        try {
            chunkMapper.delete(
                    Wrappers.lambdaQuery(ChunkDO.class)
                            .eq(ChunkDO::getDocumentMd5, documentMd5)
            );
            log.debug("已删除文档 chunk: {}", documentMd5);
        } catch (Exception e) {
            log.warn("回滚 chunk 失败: {}", documentMd5, e);
        }
    }
}
