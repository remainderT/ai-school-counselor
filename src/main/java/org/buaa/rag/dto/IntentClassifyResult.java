package org.buaa.rag.dto;

import lombok.Data;

/**
 * LLM 结构化意图分类结果
 */
@Data
public class IntentClassifyResult {

    private String level1;
    private String level2;
    private Double confidence;
    private String toolName;
    private String clarify;
}
