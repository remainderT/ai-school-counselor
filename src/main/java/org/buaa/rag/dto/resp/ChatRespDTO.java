package org.buaa.rag.dto.resp;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话响应DTO
 * 包含模型回复和参考资料
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRespDTO {
    private String response;
    private List<RetrievalMatch> sources;
    private Long messageId;
}
