package org.buaa.rag.dao.entity;

import org.buaa.rag.common.database.BaseDO;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
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

    @TableId(type = IdType.AUTO)
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
     * 示例问题 JSON 数组
     */
    private String examplesJson;

    /**
     * 关联知识库ID
     */
    private Long knowledgeBaseId;

    /**
     * 动作服务名（工具名）
     */
    private String actionService;

    /**
     * MCP 工具ID
     */
    private String mcpToolId;

    /**
     * 节点级检索 topK
     */
    private Integer topK;

    /**
     * 是否启用：1 启用，0 停用
     */
    private Integer enabled;
}
