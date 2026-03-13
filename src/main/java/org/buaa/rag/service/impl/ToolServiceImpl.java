package org.buaa.rag.service.impl;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.dto.IntentDecision;
import org.buaa.rag.service.ToolService;
import org.buaa.rag.tool.CounselorTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 工具路由执行器
 */
@Slf4j
@Service
public class ToolServiceImpl implements ToolService {

    private static final String TOOL_AGENT_PROMPT = PromptTemplateLoader.load("tool-agent.st", """
你是高校辅导员工具编排助手。
请优先调用指定工具并基于工具结果作答：
1. 回答必须使用简体中文。
2. 先给结论，再给必要步骤。
3. 当参数不足时，明确指出缺失参数。
""");

    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final CounselorTools counselorTools;

    public ToolServiceImpl(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                           CounselorTools counselorTools) {
        this.chatClientBuilderProvider = chatClientBuilderProvider;
        this.counselorTools = counselorTools;
    }

    @Override
    public String execute(String userId, String userQuery, IntentDecision decision) {
        if (decision == null || decision.getToolName() == null) {
            return "未找到可用的工具处理该请求。";
        }

        String tool = decision.getToolName();
        String agentResult = executeByFunctionCalling(userId, userQuery, tool);
        if (agentResult != null && !agentResult.isBlank()) {
            return agentResult;
        }

        // 回退到确定性逻辑，确保无模型时也可工作
        return switch (tool) {
            case "leave" -> handleLeave(userId, userQuery);
            case "repair" -> handleRepair(userId, userQuery);
            case "score" -> handleScore(userId);
            case "weather" -> handleWeather(userId, userQuery);
            default -> "该需求暂未接入自动处理，请稍后再试。";
        };
    }

    private String executeByFunctionCalling(String userId, String userQuery, String tool) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            return null;
        }

        String toolName = resolveToolName(tool);
        if (toolName == null) {
            return null;
        }

        try {
            String userPrompt = "用户ID: " + safeValue(userId, "anonymous") + "\n"
                + "指定工具: " + toolName + "\n"
                + "用户问题: " + safeValue(userQuery, "") + "\n"
                + "请调用工具后给出回答。";

            String content = builder.build()
                .prompt()
                .system(TOOL_AGENT_PROMPT)
                .user(userPrompt)
                .tools(counselorTools)
                .toolNames(toolName)
                .options(ChatOptions.builder().temperature(0.1).maxTokens(512).build())
                .call()
                .content();

            if (content == null || content.isBlank()) {
                return null;
            }
            return content;
        } catch (Exception e) {
            log.warn("Function Calling 执行失败, tool={}, error={}", toolName, e.getMessage());
            return null;
        }
    }

    private String resolveToolName(String tool) {
        return switch (tool) {
            case "score" -> "queryGrade";
            case "leave" -> "createLeaveDraft";
            case "repair" -> "createRepairTicket";
            case "weather" -> "queryWeatherByMcp";
            default -> null;
        };
    }

    private String handleLeave(String userId, String userQuery) {
        log.info("触发请假工具, userId={}, query={}", userId, userQuery);
        CounselorTools.LeaveDraftToolResult result = counselorTools.createLeaveDraft(
            safeValue(userId, "anonymous"),
            null,
            null,
            userQuery
        );
        return "已创建请假草稿，状态：" + result.status()
            + "；后续操作：" + result.nextAction();
    }

    private String handleRepair(String userId, String userQuery) {
        log.info("触发报修工具, userId={}, query={}", userId, userQuery);
        CounselorTools.RepairDraftToolResult result = counselorTools.createRepairTicket(
            safeValue(userId, "anonymous"),
            null,
            userQuery
        );
        return "已创建报修草稿，状态：" + result.status()
            + "；后续操作：" + result.nextAction();
    }

    private String handleScore(String userId) {
        log.info("触发成绩查询工具, userId={}", userId);
        CounselorTools.GradeToolResult result = counselorTools.queryGrade(
            safeValue(userId, "anonymous")
        );
        return "成绩查询结果：当前绩点 " + result.gpa()
            + "，已修学分 " + result.creditCompleted()
            + "，风险课程 " + result.riskCourses() + " 门。";
    }

    private String handleWeather(String userId, String userQuery) {
        log.info("触发天气查询工具, userId={}, query={}", userId, userQuery);
        CounselorTools.WeatherToolResult result = counselorTools.queryWeatherByMcp(
            null,
            "current",
            3,
            safeValue(userId, "anonymous"),
            userQuery
        );
        if ("NEED_CITY".equals(result.status())) {
            return result.summary();
        }
        if ("UNAVAILABLE".equals(result.status())) {
            return result.summary();
        }
        String source = result.source() == null || result.source().isBlank() ? "unknown" : result.source();
        return result.summary() + "\n（天气数据来源: " + source + "）";
    }

    private String safeValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
