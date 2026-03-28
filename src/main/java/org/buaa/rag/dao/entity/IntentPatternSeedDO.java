package org.buaa.rag.dao.entity;

import org.buaa.rag.common.database.BaseDO;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 意图语义路由样本种子
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("intent_pattern_seed")
public class IntentPatternSeedDO extends BaseDO {

    private Long id;

    /**
     * 意图编码，如 guide:leave
     */
    private String intentCode;

    /**
     * 一级意图名称
     */
    private String level1;

    /**
     * 二级意图名称
     */
    private String level2;

    /**
     * 示例问题样本
     */
    private String sample;

    /**
     * 排序字段
     */
    private Integer sortOrder;

    /**
     * 是否启用：1 启用，0 停用
     */
    private Integer enabled;
}
