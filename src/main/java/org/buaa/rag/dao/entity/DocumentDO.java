package org.buaa.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import org.buaa.rag.common.database.BaseDO;

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

    private int processingStatus; // 0-待处理 1-处理中 2-已完成 -1-失败

    private Long userId;

    private String visibility;

    private LocalDateTime uploadedAt;

    private LocalDateTime processedAt;

}
