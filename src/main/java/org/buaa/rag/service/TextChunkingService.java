package org.buaa.rag.service;

import java.util.List;

/**
 * 文本智能分块服务
 * <p>
 * 将长文本按段落 → 句子 → 分词逐级拆分为不超过上限的文本片段，
 * 保持语义完整性。
 */
public interface TextChunkingService {

    /**
     * 将文本分割为多个片段
     *
     * @param fullText     完整文本
     * @param maxChunkSize 每个片段的最大字符数
     * @return 分块结果
     */
    List<String> chunk(String fullText, int maxChunkSize);
}
