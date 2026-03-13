package org.buaa.rag.dao.entity;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ES 索引结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ESIndexDO {

    private String documentId;

    private String sourceMd5;

    private Integer segmentNumber;

    private String textPayload;

    private String encoderVersion;

    private List<Float> vectorEmbedding;
}
