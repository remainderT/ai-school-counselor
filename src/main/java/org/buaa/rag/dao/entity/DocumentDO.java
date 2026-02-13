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
 * 存储上传文档的元数据信息
 */
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

    private int processingStatus; // 0-处理中 1-已完成

    private String userId;

    private String visibility;

    private String docType;

    private String department;

    private String policyYear;

    private String tags;

    private LocalDateTime uploadedAt;

    private LocalDateTime processedAt;

}
