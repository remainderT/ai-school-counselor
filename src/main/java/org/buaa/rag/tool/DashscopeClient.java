package org.buaa.rag.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.buaa.rag.core.online.chat.StreamCancellationHandle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * DashScope HTTP 客户端
 */
@Slf4j
@Component
public class DashscopeClient {

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    @Value("${spring.ai.dashscope.base-url:" + DEFAULT_BASE_URL + "}")
    private String baseUrl;

    @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}")
    private String chatModel;

    @Value("${spring.ai.dashscope.embedding.options.model:text-embedding-v4}")
    private String embeddingModel;

    public String chatCompletion(String systemPrompt,
                                 String userPrompt,
                                 Double temperature,
                                 Double topP,
                                 Integer maxTokens) {
        if (!StringUtils.hasText(apiKey)) {
            log.warn("DashScope API Key 未配置，跳过聊天调用");
            return "";
        }
        try {
            ObjectNode payload = buildChatPayload(systemPrompt, userPrompt, temperature, topP, maxTokens);
            String responseBody = post("/api/v1/services/aigc/text-generation/generation", payload.toString());
            if (!StringUtils.hasText(responseBody)) {
                return "";
            }
            return extractText(objectMapper.readTree(responseBody));
        } catch (Exception e) {
            log.warn("DashScope 聊天调用失败: {}", e.getMessage());
            return "";
        }
    }

    public void streamChatCompletion(String systemPrompt,
                                     String userPrompt,
                                     Double temperature,
                                     Double topP,
                                     Integer maxTokens,
                                     Consumer<String> chunkHandler) throws Exception {
        ObjectNode payload = buildChatPayload(systemPrompt, userPrompt, temperature, topP, maxTokens);
        streamChatCompletion(payload, chunkHandler, null);
    }

    public void streamChatCompletion(List<Map<String, String>> messages,
                                     Double temperature,
                                     Double topP,
                                     Integer maxTokens,
                                     Consumer<String> chunkHandler) throws Exception {
        ObjectNode payload = buildChatPayload(messages, temperature, topP, maxTokens);
        streamChatCompletion(payload, chunkHandler, null);
    }

    /**
     * 支持取消句柄的流式调用重载。
     */
    public void streamChatCompletion(List<Map<String, String>> messages,
                                     Double temperature,
                                     Double topP,
                                     Integer maxTokens,
                                     Consumer<String> chunkHandler,
                                     StreamCancellationHandle cancelHandle) throws Exception {
        ObjectNode payload = buildChatPayload(messages, temperature, topP, maxTokens);
        streamChatCompletion(payload, chunkHandler, cancelHandle);
    }

    private void streamChatCompletion(ObjectNode payload,
                                      Consumer<String> chunkHandler,
                                      StreamCancellationHandle cancelHandle) throws Exception {
        if (!StringUtils.hasText(apiKey)) {
            log.warn("DashScope API Key 未配置，跳过流式聊天调用");
            return;
        }

        payload.put("stream", true);
        payload.with("parameters").put("incremental_output", true);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(resolveBaseUrl() + "/api/v1/services/aigc/text-generation/generation"))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer " + apiKey.trim())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();

        HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        ensureStreamSuccess(response);
        consumeStream(response, chunkHandler, cancelHandle);
    }

    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!StringUtils.hasText(apiKey)) {
            log.warn("DashScope API Key 未配置，跳过向量化调用");
            return List.of();
        }
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", safeValue(embeddingModel, "text-embedding-v4"));

            ObjectNode input = payload.putObject("input");
            ArrayNode textArray = input.putArray("texts");
            for (String text : texts) {
                textArray.add(text == null ? "" : text);
            }

            String responseBody = post("/api/v1/services/embeddings/text-embedding/text-embedding", payload.toString());
            if (!StringUtils.hasText(responseBody)) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddings = root.path("output").path("embeddings");
            if (!embeddings.isArray() || embeddings.isEmpty()) {
                return List.of();
            }

            List<float[]> result = new ArrayList<>(embeddings.size());
            for (JsonNode item : embeddings) {
                JsonNode vector = item.path("embedding");
                if (!vector.isArray() || vector.isEmpty()) {
                    continue;
                }
                float[] values = new float[vector.size()];
                for (int i = 0; i < vector.size(); i++) {
                    values[i] = (float) vector.get(i).asDouble(0.0);
                }
                result.add(values);
            }
            return result;
        } catch (Exception e) {
            log.warn("DashScope 向量化调用失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String post(String apiPath, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(resolveBaseUrl() + apiPath))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + apiKey.trim())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("DashScope 请求失败, status={}, body={}", response.statusCode(), response.body());
            return "";
        }
        return response.body();
    }

    private void ensureStreamSuccess(HttpResponse<java.io.InputStream> response) throws Exception {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("DashScope 流式请求失败, status=" + response.statusCode() + ", body=" + body);
        }
    }

    private void consumeStream(HttpResponse<java.io.InputStream> response,
                               Consumer<String> chunkHandler,
                               StreamCancellationHandle cancelHandle) throws Exception {
        String previousText = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // LLM 层取消检查
                if (cancelHandle != null && cancelHandle.isCancelled()) {
                    log.debug("流式读取被取消，提前退出");
                    break;
                }
                if (line.isBlank() || line.startsWith(":") || !line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (!StringUtils.hasText(data)) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }

                JsonNode root = objectMapper.readTree(data);
                String currentText = extractText(root);
                if (!StringUtils.hasText(currentText)) {
                    continue;
                }

                String delta = currentText;
                if (StringUtils.hasText(previousText) && currentText.startsWith(previousText)) {
                    delta = currentText.substring(previousText.length());
                }
                previousText = currentText;
                if (StringUtils.hasText(delta) && chunkHandler != null) {
                    chunkHandler.accept(delta);
                }
            }
        }
    }

    private ObjectNode buildChatPayload(String systemPrompt,
                                        String userPrompt,
                                        Double temperature,
                                        Double topP,
                                        Integer maxTokens) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (StringUtils.hasText(systemPrompt)) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        if (StringUtils.hasText(userPrompt)) {
            messages.add(Map.of("role", "user", "content", userPrompt));
        }
        return buildChatPayload(messages, temperature, topP, maxTokens);
    }

    private ObjectNode buildChatPayload(List<Map<String, String>> messages,
                                        Double temperature,
                                        Double topP,
                                        Integer maxTokens) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", safeValue(chatModel, "qwen-plus"));

        ObjectNode input = payload.putObject("input");
        ArrayNode messageArray = input.putArray("messages");
        if (messages != null) {
            for (Map<String, String> message : messages) {
                if (message == null) {
                    continue;
                }
                String content = message.get("content");
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                ObjectNode item = messageArray.addObject();
                item.put("role", normalizeRole(message.get("role")));
                item.put("content", content);
            }
        }

        ObjectNode parameters = payload.putObject("parameters");
        if (temperature != null) {
            parameters.put("temperature", temperature);
        }
        if (topP != null) {
            parameters.put("top_p", topP);
        }
        if (maxTokens != null && maxTokens > 0) {
            parameters.put("max_tokens", maxTokens);
        }
        return payload;
    }

    private String extractText(JsonNode root) {
        JsonNode outputText = root.path("output").path("text");
        if (outputText.isTextual()) {
            return outputText.asText("");
        }

        JsonNode choices = root.path("output").path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isTextual()) {
                return content.asText("");
            }
        }
        return "";
    }

    private String resolveBaseUrl() {
        if (!StringUtils.hasText(baseUrl)) {
            return DEFAULT_BASE_URL;
        }
        String normalized = baseUrl.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private String safeValue(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String normalizeRole(String role) {
        if (!StringUtils.hasText(role)) {
            return "user";
        }
        String normalized = role.trim().toLowerCase();
        return switch (normalized) {
            case "system", "assistant", "user" -> normalized;
            default -> "user";
        };
    }
}
