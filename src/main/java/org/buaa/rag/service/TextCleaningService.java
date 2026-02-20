package org.buaa.rag.service;

/**
 * 文本清洗服务
 */
public interface TextCleaningService {

    /**
     * 清洗提取文本，并按上限截断
     */
    String clean(String rawText, int maxChars);
}
