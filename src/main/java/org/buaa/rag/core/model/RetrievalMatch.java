package org.buaa.rag.core.model;

import org.buaa.rag.common.enums.SearchChannelType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索匹配结果，封装搜索引擎返回的单条命中记录。
 *
 * <p>每条匹配包含文档定位信息（{@code fileMd5} + {@code chunkId}）、
 * 匹配文本、相关性分数以及来源通道标记。通道标记由检索通道在产出
 * 结果时设置，后处理链可据此实现通道感知的去重与排序。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalMatch {

    private Long documentId;

    private String fileMd5;

    private Integer chunkId;

    private String textContent;

    private Double relevanceScore;

    private String sourceFileName;

    /** 产出该匹配的检索通道类型（去重时用于区分来源优先级） */
    private SearchChannelType channelType;

    /**
     * 兼容构造（不含文件名和通道类型）。
     */
    public RetrievalMatch(String fileMd5, Integer chunkId, String textContent, Double score) {
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.relevanceScore = score;
    }

    /**
     * 构建唯一标识 key，用于跨通道去重和合并。
     * <p>格式：{@code fileMd5:chunkId}
     */
    public String matchKey() {
        String md5 = fileMd5 == null ? "" : fileMd5;
        String chunk = chunkId == null ? "null" : String.valueOf(chunkId);
        return md5 + ":" + chunk;
    }
}
