package org.buaa.rag.dao.entity;

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

    private Long id;

    private String documentMd5;

    private Integer fragmentIndex;

    private String textData;

    private String encodingModel;
}
