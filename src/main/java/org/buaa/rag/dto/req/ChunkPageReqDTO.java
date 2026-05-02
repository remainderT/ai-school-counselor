package org.buaa.rag.dto.req;

import org.buaa.rag.dao.entity.ChunkDO;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文档 chunk 分页查询请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ChunkPageReqDTO extends Page<ChunkDO> {

    /**
     * chunk 内容关键字
     */
    private String keyword;
}
