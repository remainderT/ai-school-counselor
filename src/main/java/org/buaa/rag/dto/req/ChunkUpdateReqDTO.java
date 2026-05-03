package org.buaa.rag.dto.req;

import jakarta.validation.constraints.NotBlank;

import lombok.Data;

/**
 * Chunk 内容编辑请求
 */
@Data
public class ChunkUpdateReqDTO {

    /**
     * chunk 文本内容
     */
    @NotBlank(message = "chunk 文本内容不能为空")
    private String textData;
}
