package org.buaa.rag.core.online.rerank;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.properties.RagProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * 百炼 Rerank API 客户端。
 * <p>
 * 调用 DashScope 的 text-rerank 接口对候选文档做精排，
 * 返回结构化的 index + relevance_score，无需解析 LLM 文本输出。
 */
@Slf4j
@Component
public class DashScopeRerankClient implements RerankClient {

    private static final String RERANK_API_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final RagProperties.RerankProvider config;
    private final String fallbackApiKey;

    public DashScopeRerankClient(RagProperties ragProperties,
                                 @org.springframework.beans.factory.annotation.Value("${spring.ai.dashscope.api-key:}") String globalApiKey) {
        this.config = ragProperties.getRerank().getDashscope();
        this.fallbackApiKey = globalApiKey;
    }

    @Override
    public String provider() {
        return "dashscope";
    }

    @Override
    public List<RetrievalMatch> rerank(String query, List<RetrievalMatch> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 按文本内容去重
        List<RetrievalMatch> dedup = deduplicate(candidates);

        if (topN <= 0 || dedup.size() <= 1) {
            return dedup.size() > topN && topN > 0
                    ? dedup.subList(0, topN) : dedup;
        }

        return doRerank(query, dedup, topN);
    }

    private List<RetrievalMatch> doRerank(String query, List<RetrievalMatch> candidates, int topN) {
        String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : fallbackApiKey;
        String baseUrl = config.getBaseUrl();
        String model = config.getModel();

        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("DashScope Rerank API Key 未配置 (rag.rerank.dashscope.api-key 和 spring.ai.dashscope.api-key 均为空)");
        }

        // 构造请求体
        ObjectNode reqBody = objectMapper.createObjectNode();
        reqBody.put("model", StringUtils.hasText(model) ? model : "gte-rerank");

        ObjectNode input = reqBody.putObject("input");
        input.put("query", query);
        ArrayNode documentsArray = input.putArray("documents");
        for (RetrievalMatch match : candidates) {
            String text = match.getTextContent();
            documentsArray.add(text == null ? "" : text);
        }

        ObjectNode parameters = reqBody.putObject("parameters");
        parameters.put("top_n", topN);
        parameters.put("return_documents", false);

        // 发送请求
        String url = resolveBaseUrl(baseUrl) + RERANK_API_PATH;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reqBody.toString()))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("百炼 Rerank 请求异常: " + e.getMessage(), e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("百炼 Rerank 请求失败: HTTP " + response.statusCode()
                    + ", body=" + response.body());
        }

        // 解析响应
        return parseResponse(response.body(), candidates, topN);
    }

    private List<RetrievalMatch> parseResponse(String responseBody,
                                                List<RetrievalMatch> candidates,
                                                int topN) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode results = root.path("output").path("results");
            if (!results.isArray() || results.isEmpty()) {
                throw new RuntimeException("百炼 Rerank 响应缺少 results");
            }

            List<RetrievalMatch> reranked = new ArrayList<>();
            for (JsonNode item : results) {
                if (!item.has("index")) {
                    continue;
                }
                int idx = item.get("index").asInt(-1);
                if (idx < 0 || idx >= candidates.size()) {
                    continue;
                }

                RetrievalMatch src = candidates.get(idx);
                if (item.has("relevance_score") && !item.get("relevance_score").isNull()) {
                    src.setRelevanceScore(item.get("relevance_score").asDouble());
                }
                reranked.add(src);

                if (reranked.size() >= topN) {
                    break;
                }
            }

            // 不足 topN 时用剩余候选补充
            if (reranked.size() < topN) {
                Set<Integer> usedIndices = new HashSet<>();
                for (JsonNode item : results) {
                    if (item.has("index")) {
                        usedIndices.add(item.get("index").asInt(-1));
                    }
                }
                for (int i = 0; i < candidates.size() && reranked.size() < topN; i++) {
                    if (!usedIndices.contains(i)) {
                        reranked.add(candidates.get(i));
                    }
                }
            }

            return reranked;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("百炼 Rerank 响应解析失败: " + e.getMessage(), e);
        }
    }

    private List<RetrievalMatch> deduplicate(List<RetrievalMatch> candidates) {
        List<RetrievalMatch> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievalMatch m : candidates) {
            String key = m.matchKey();
            if (seen.add(key)) {
                dedup.add(m);
            }
        }
        return dedup;
    }

    private String resolveBaseUrl(String baseUrl) {
        String url = StringUtils.hasText(baseUrl) ? baseUrl.trim() : "https://dashscope.aliyuncs.com";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
