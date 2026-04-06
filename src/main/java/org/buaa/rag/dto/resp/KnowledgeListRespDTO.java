package org.buaa.rag.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库列表响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeListRespDTO {

    private Long id;

    private String name;

    private String description;

    /**
     * 该知识库下的文档数量
     */
    private Long documentCount;
}
