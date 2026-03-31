package org.buaa.rag.dto.resp;

import lombok.Data;

@Data
public class ConversationSessionRespDTO {

    private String sessionId;

    private String userId;

    private String title;

    private Integer messageCount;

    private String updatedAt;
}
