package org.buaa.rag.core.online.tool.mcp;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AcademicMcpParameterExtractor {

    private static final String DEFAULT_SYSTEM_PROMPT = PromptTemplateLoader.load("mcp-parameter-extract.st");
    private static final String USER_PROMPT_TEMPLATE = "mcp-parameter-extract-user.st";
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final LlmChat llmChat;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> extractParameters(String userQuery,
                                                 LocalMcpToolDefinition toolDefinition,
                                                 String customPromptTemplate) {
        if (toolDefinition == null || toolDefinition.parameters() == null || toolDefinition.parameters().isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> defaultParams = buildDefaultParameters(toolDefinition);
        String currentDate = DATE_FORMATTER.format(LocalDate.now(ZONE_ID));
        String systemPromptTemplate = StringUtils.hasText(customPromptTemplate)
            ? customPromptTemplate.trim()
            : DEFAULT_SYSTEM_PROMPT;
        String systemPrompt = renderInlineTemplate(systemPromptTemplate, Map.of(
            "current_date", currentDate
        ));
        String userPrompt = PromptTemplateLoader.render(USER_PROMPT_TEMPLATE, Map.of(
            "tool_definition", buildToolDefinition(toolDefinition),
            "user_question", userQuery == null ? "" : userQuery,
            "current_date", currentDate
        ));

        String raw = llmChat.generateCompletion(systemPrompt, userPrompt, 600, 0.1, 0.3);
        if (!StringUtils.hasText(raw)) {
            return defaultParams;
        }

        try {
            Map<String, Object> extracted = parseJsonResponse(raw, toolDefinition);
            fillDefaults(extracted, toolDefinition);
            log.info("MCP 参数提取完成, toolId={}, params={}", toolDefinition.id(), extracted);
            return extracted;
        } catch (Exception e) {
            log.warn("MCP 参数提取失败, toolId={}, response={}", toolDefinition.id(), raw, e);
            return defaultParams;
        }
    }

    private Map<String, Object> buildDefaultParameters(LocalMcpToolDefinition toolDefinition) {
        Map<String, Object> params = new LinkedHashMap<>();
        fillDefaults(params, toolDefinition);
        return params;
    }

    private String buildToolDefinition(LocalMcpToolDefinition toolDefinition) {
        StringBuilder builder = new StringBuilder();
        builder.append("工具ID: ").append(toolDefinition.id()).append("\n");
        builder.append("功能描述: ").append(toolDefinition.description()).append("\n");
        builder.append("参数列表:\n");

        toolDefinition.parameters().forEach((name, spec) -> {
            if (spec == null) {
                return;
            }
            builder.append("  - ").append(name)
                .append(" (类型: ").append(spec.type())
                .append(spec.required() ? ", 必填" : ", 可选")
                .append("): ").append(spec.description());
            if (spec.defaultValue() != null) {
                builder.append(" [默认值: ").append(spec.defaultValue()).append("]");
            }
            List<String> enumValues = spec.enumValues();
            if (enumValues != null && !enumValues.isEmpty()) {
                builder.append(" [可选值: ").append(String.join(", ", enumValues)).append("]");
            }
            builder.append("\n");
        });
        return builder.toString();
    }

    private Map<String, Object> parseJsonResponse(String raw,
                                                  LocalMcpToolDefinition toolDefinition) throws Exception {
        String cleaned = stripMarkdownCodeFence(raw);
        JsonNode root = objectMapper.readTree(cleaned);
        if (root == null || !root.isObject()) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        toolDefinition.parameters().forEach((name, spec) -> {
            JsonNode valueNode = root.get(name);
            if (valueNode == null || valueNode.isNull()) {
                return;
            }
            result.put(name, convertJsonNode(valueNode));
        });
        return result;
    }

    private void fillDefaults(Map<String, Object> params,
                              LocalMcpToolDefinition toolDefinition) {
        toolDefinition.parameters().forEach((name, spec) -> {
            if (!params.containsKey(name) && spec != null && spec.defaultValue() != null) {
                params.put(name, spec.defaultValue());
            }
        });
    }

    private Object convertJsonNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt()) {
            return node.intValue();
        }
        if (node.isLong()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isArray()) {
            return objectMapper.convertValue(node, List.class);
        }
        if (node.isObject()) {
            return objectMapper.convertValue(node, LinkedHashMap.class);
        }
        return node.asText();
    }

    private String stripMarkdownCodeFence(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        if (!cleaned.startsWith("```")) {
            return cleaned;
        }
        int firstLineBreak = cleaned.indexOf('\n');
        if (firstLineBreak < 0) {
            return cleaned.replace("```", "").trim();
        }
        String withoutStart = cleaned.substring(firstLineBreak + 1);
        int closingFence = withoutStart.lastIndexOf("```");
        if (closingFence >= 0) {
            withoutStart = withoutStart.substring(0, closingFence);
        }
        return withoutStart.trim();
    }

    private String renderInlineTemplate(String template, Map<String, String> variables) {
        if (!StringUtils.hasText(template) || variables == null || variables.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }
}
