package org.buaa.rag.service;

import java.util.List;

import org.buaa.rag.dto.ContentFragment;

/**
 * 文档向量索引服务
 * <p>
 * 负责将文本片段编码为向量，并批量写入 Elasticsearch 索引。
 */
public interface DocumentIndexingService {

    /**
     * 为文档构建向量索引
     *
     * @param documentMd5 文档 MD5
     * @param fragments   文本片段列表
     */
    void index(String documentMd5, List<ContentFragment> fragments);

    /**
     * 删除文档的全部索引
     *
     * @param documentMd5 文档 MD5
     */
    void removeIndex(String documentMd5);
}
