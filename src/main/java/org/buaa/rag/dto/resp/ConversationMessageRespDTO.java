package org.buaa.rag.dto.resp;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;

import lombok.Data;

@Data
public class ConversationMessageRespDTO {

    private Long id;

    private String role;

    private String content;

    private String createdAt;

    private List<RetrievalMatch> sources;
}
