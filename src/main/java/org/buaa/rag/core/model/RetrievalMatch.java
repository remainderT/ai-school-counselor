package org.buaa.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索匹配结果DTO
 * 封装搜索返回的单条结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalMatch {

    private String fileMd5;

    private Integer chunkId;

    private String textContent;

    private Double relevanceScore;

    private String sourceFileName;

    /**
     * 构造函数（不包含文件名）
     */
    public RetrievalMatch(String fileMd5, Integer chunkId, String textContent, Double score) {
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.relevanceScore = score;
    }

    /**
     * 判断是否为高相关度结果
     */
    public boolean isHighlyRelevant() {
        return relevanceScore != null && relevanceScore > 0.8;
    }

    /**
     * 获取简短预览
     */
    public String getPreview(int maxLength) {
        if (textContent == null) {
            return "";
        }
        if (textContent.length() <= maxLength) {
            return textContent;
        }
        return textContent.substring(0, maxLength) + "...";
    }
}
