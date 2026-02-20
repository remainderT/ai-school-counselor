package org.buaa.rag.service;

import org.buaa.rag.dto.QueryPlan;

/**
 * 查询分析服务接口
 * 负责查询改写与 HyDE 生成
 */
public interface QueryAnalysisService {

    /**
     * 创建查询计划
     */
    QueryPlan createPlan(String userQuery);

}
