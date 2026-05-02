package org.buaa.rag.dto.resp;

import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档分页响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentPageRespDTO {

    private Long id;

    private Long knowledgeId;

    private String originalFileName;

    private Long fileSizeBytes;

    private Integer processingStatus;

    private String processingStatusDesc;

    private Date createTime;

    private Integer chunkCount;
}
