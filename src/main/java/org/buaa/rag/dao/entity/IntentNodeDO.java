package org.buaa.rag.dao.entity;

import org.buaa.rag.common.database.BaseDO;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 意图树节点实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("intent_node")
public class IntentNodeDO extends BaseDO {

    private Long id;

    /**
     * 节点业务ID（树内唯一）
     */
    private String nodeId;

    /**
     * 节点名称
     */
    private String nodeName;

    /**
     * 父节点ID，为空表示根节点
     */
    private String parentId;

    /**
     * 节点类型：GROUP/RAG_QA/API_ACTION/CHITCHAT
     */
    private String nodeType;

    /**
     * 节点语义描述
     */
    private String description;

    /**
     * 场景 Prompt 模板
     */
    private String promptTemplate;

    /**
     * 短规则片段
     */
    private String promptSnippet;

    /**
     * MCP 参数提取提示词
     */
    private String paramPromptTemplate;

    /**
     * 关键词 JSON 数组
     */
    private String keywordsJson;

    /**
     * 关联知识库ID（字符串，兼容已有检索逻辑）
     */
    private String knowledgeBaseId;

    /**
     * 动作服务名（工具名）
     */
    private String actionService;

    /**
     * 节点层级：DOMAIN/CATEGORY/TOPIC
     */
    private String nodeLevel;

    /**
     * 节点类型语义：KB/MCP/SYSTEM
     */
    private String nodeKind;

    /**
     * MCP 工具ID
     */
    private String mcpToolId;

    /**
     * 节点级检索 topK
     */
    private Integer topK;

    /**
     * 排序字段
     */
    private Integer sortOrder;

    /**
     * 是否启用：1 启用，0 停用
     */
    private Integer enabled;
}
