package org.buaa.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档 chunk 实体
 * 存储文档切分后的 chunk 文本内容
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chunk")
public class ChunkDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Integer fragmentIndex;

    private String textData;

    private String encodingModel;

    /**
     * chunk 文本 MD5 哈希值
     */
    private String md5Hash;

    /**
     * token 估算值
     */
    private Integer tokenEstimate;

    /**
     * 是否启用 1：启用 0：禁用
     */
    private Integer enabled;

    /**
     * 删除标识 0：未删除 1：已删除
     */
    private Integer delFlag;
}
