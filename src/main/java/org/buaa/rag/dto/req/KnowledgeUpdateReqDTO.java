package org.buaa.rag.dto.req;

import lombok.Data;

@Data
public class KnowledgeUpdateReqDTO {

    private String name;

    private String description;

    /**
     * private / public
     */
    private String visibility;
}
