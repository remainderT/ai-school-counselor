package org.buaa.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 索引内容模型
 * 用于Elasticsearch存储的文档结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("document_records")
public class IndexedContentDO {

    private String documentId;

    private String sourceMd5;

    private Integer segmentNumber;

    private String textPayload;

    private float[] vectorEmbedding;

    private String encoderVersion;
}
