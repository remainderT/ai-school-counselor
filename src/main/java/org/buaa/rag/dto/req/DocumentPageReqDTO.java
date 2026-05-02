package org.buaa.rag.dto.req;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文档分页查询请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DocumentPageReqDTO extends Page<org.buaa.rag.dao.entity.DocumentDO> {

    /**
     * 知识库 ID
     */
    private Long knowledgeId;

    /**
     * 文档名称
     */
    private String name;
}
