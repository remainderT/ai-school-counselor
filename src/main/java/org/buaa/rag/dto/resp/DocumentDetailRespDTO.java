package org.buaa.rag.dto.resp;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import org.buaa.rag.dao.entity.ChunkDO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档详情响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDetailRespDTO {

    private Long id;

    private Long knowledgeId;

    private String originalFileName;

    private Long fileSizeBytes;

    private Integer processingStatus;

    private String processingStatusDesc;

    private Date createTime;

    private Integer chunkCount;

    private String sourceUrl;

    private Integer scheduleEnabled;

    private String scheduleCron;

    private String chunkMode;

    private LocalDateTime nextRefreshAt;

    private LocalDateTime lastRefreshAt;

    private String failureReason;

    private LocalDateTime processedAt;

    private List<ChunkDO> chunks;
}
