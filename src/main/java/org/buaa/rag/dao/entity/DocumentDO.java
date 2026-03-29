package org.buaa.rag.dao.entity;

import java.time.LocalDateTime;

import org.buaa.rag.common.database.BaseDO;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档记录实体
 * */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("document")
public class DocumentDO extends BaseDO {

    private Long id;

    private String md5Hash;

    private String originalFileName;

    private long fileSizeBytes;

    private int processingStatus;

    @TableField(exist = false)
    private String processingStatusDesc;

    private Long userId;

    private Long knowledgeId;

    private String failureReason;

    private LocalDateTime processedAt;
}
