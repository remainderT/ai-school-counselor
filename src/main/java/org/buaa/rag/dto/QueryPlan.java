package org.buaa.rag.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询规划结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryPlan {
    private String originalQuery;
    private List<String> rewrittenQueries;
    private String hydeAnswer;
}
