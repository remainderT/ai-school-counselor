package org.buaa.rag.common.enums;

/**
 * 检索通道类别，用于后处理阶段区分结果来源。
 */
public enum SearchChannelType {

    /** 基于意图的精准定向召回 */
    INTENT_DIRECTED,

    /** 不依赖意图的全局向量召回 */
    VECTOR_GLOBAL
}
