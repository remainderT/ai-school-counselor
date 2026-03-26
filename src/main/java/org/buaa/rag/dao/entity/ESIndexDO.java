package org.buaa.rag.dao.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

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

    private String id;

    @JsonProperty("document_id")
    private Long documentId;

    @JsonProperty("fragment_index")
    private Integer fragmentIndex;

    @JsonProperty("text_data")
    private String textData;

    @JsonProperty("encoding_model")
    private String encodingModel;
}
