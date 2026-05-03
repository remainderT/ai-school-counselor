package org.buaa.rag.dto.req;

import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class ConversationSessionCreateReqDTO {

    @Size(max = 100, message = "会话标题长度不能超过 100 个字符")
    private String title;
}
