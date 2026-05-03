package org.buaa.rag.core.online.rewrite;

import java.util.LinkedHashMap;
import java.util.Map;

import org.buaa.rag.properties.RagProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 查询词项归一化服务：在 LLM 改写之前，对用户原始问题做同义词/别名映射。
 *
 * <p>例如：
 * <ul>
 *   <li>"学分绩点" → "绩点"</li>
 *   <li>"辅导员" → "导师"</li>
 *   <li>"毕设" → "毕业设计"</li>
 * </ul>
 *
 * <p>映射规则来自 {@code rag.query-preprocess.term-mapping} 配置，
 * 按最长匹配优先（先替换较长的词条），避免短词吞并长词。
 *
 * <p>负责查询词项的同义词归一化处理。
 */
@Slf4j
@Service
public class QueryTermMappingService {

    private final RagProperties ragProperties;

    public QueryTermMappingService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 将查询中的已知同义词/别名替换为标准词，提升改写与意图识别精度。
     *
     * @param query 用户原始查询，可为 null
     * @return 归一化后的查询；若 mapping 为空或未命中，返回原始 query
     */
    public String normalize(String query) {
        if (!StringUtils.hasText(query)) {
            return query;
        }

        RagProperties.QueryPreprocess cfg = ragProperties.getQueryPreprocess();
        if (cfg == null) {
            return query;
        }

        Map<String, String> rawMapping = cfg.getTermMapping();
        if (rawMapping == null || rawMapping.isEmpty()) {
            return query;
        }

        // 按 key 长度降序排列，保证最长匹配优先
        LinkedHashMap<String, String> sorted = new LinkedHashMap<>();
        rawMapping.entrySet().stream()
            .filter(e -> StringUtils.hasText(e.getKey()) && StringUtils.hasText(e.getValue()))
            .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
            .forEach(e -> sorted.put(e.getKey(), e.getValue()));

        if (sorted.isEmpty()) {
            return query;
        }

        String result = query;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (result.contains(entry.getKey())) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
        }

        if (!result.equals(query)) {
            log.debug("词项归一化命中 | 原始='{}' → 归一化='{}'", compact(query), compact(result));
        }
        return result;
    }

    private String compact(String text) {
        if (text == null) return "";
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
