package org.buaa.rag.tool;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.core.online.chat.StreamCancellationHandle;
import org.buaa.rag.core.trace.RagTraceNode;
import org.buaa.rag.properties.LlmProperties;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 大语言模型聊天服务
 */
@Slf4j
@Service
public class LlmChat {
    private static final Pattern MEANINGFUL_PATTERN = Pattern.compile("[\\p{IsHan}\\p{L}\\p{N}]");

    private final LlmProperties llmProperties;
    private final DashscopeClient dashscopeClient;

    public LlmChat(LlmProperties llmProperties, DashscopeClient dashscopeClient) {
        this.llmProperties = llmProperties;
        this.dashscopeClient = dashscopeClient;
    }

    public void streamResponse(String userQuery,
                               String referenceContext,
                               List<Map<String, String>> conversationHistory,
                               Consumer<String> chunkHandler,
                               Consumer<Throwable> errorHandler,
                               Runnable completionHandler) {
        streamResponse(userQuery, referenceContext, conversationHistory,
            null, null, chunkHandler, errorHandler, completionHandler);
    }

    public void streamResponse(String userQuery,
                               String referenceContext,
                               List<Map<String, String>> conversationHistory,
                               Double temperatureOverride,
                               Double topPOverride,
                               Consumer<String> chunkHandler,
                               Consumer<Throwable> errorHandler,
                               Runnable completionHandler) {
        AtomicBoolean completionFlag = new AtomicBoolean(false);
        Double temperature = temperatureOverride != null ? temperatureOverride : temperature();
        Double topP = topPOverride != null ? topPOverride : topP();
        try {
            dashscopeClient.streamChatCompletion(
                buildSystemPrompt(referenceContext),
                buildUserPrompt(userQuery, conversationHistory),
                temperature,
                topP,
                maxTokens(null),
                chunk -> {
                    if (chunkHandler == null) {
                        return;
                    }
                    String cleaned = sanitizeChunk(chunk);
                    if (hasMeaningfulText(cleaned)) {
                        chunkHandler.accept(cleaned);
                    }
                }
            );
        } catch (Exception e) {
            notifyError(errorHandler, e);
        } finally {
            notifyCompletion(completionFlag, completionHandler);
        }
    }

    public String generateCompletion(String systemPrompt,
                                     String userPrompt,
                                     Integer maxTokens) {
        return generateCompletion(systemPrompt, userPrompt, maxTokens, null, null);
    }

    public String generateCompletion(String systemPrompt,
                                     String userPrompt,
                                     Integer maxTokens,
                                     Double temperatureOverride,
                                     Double topPOverride) {
        try {
            String content = dashscopeClient.chatCompletion(
                systemPrompt,
                userPrompt,
                temperatureOverride != null ? temperatureOverride : temperature(),
                topPOverride != null ? topPOverride : topP(),
                maxTokens(maxTokens)
            );
            return content == null ? "" : content;
        } catch (Exception e) {
            log.warn("LLM 同步调用失败: {}", e.getMessage());
            return "";
        }
    }

    public void streamResponse(List<Map<String, String>> messages,
                               Double temperatureOverride,
                               Double topPOverride,
                               Integer maxTokensOverride,
                               Consumer<String> chunkHandler,
                               Consumer<Throwable> errorHandler,
                               Runnable completionHandler) {
        AtomicBoolean completionFlag = new AtomicBoolean(false);
        Double temperature = temperatureOverride != null ? temperatureOverride : temperature();
        Double topP = topPOverride != null ? topPOverride : topP();
        try {
            dashscopeClient.streamChatCompletion(
                messages,
                temperature,
                topP,
                maxTokens(maxTokensOverride),
                chunk -> {
                    if (chunkHandler == null) {
                        return;
                    }
                    String cleaned = sanitizeChunk(chunk);
                    if (hasMeaningfulText(cleaned)) {
                        chunkHandler.accept(cleaned);
                    }
                }
            );
        } catch (Exception e) {
            notifyError(errorHandler, e);
        } finally {
            notifyCompletion(completionFlag, completionHandler);
        }
    }

    public void streamResponse(List<Map<String, String>> messages,
                               Double temperatureOverride,
                               Double topPOverride,
                               Consumer<String> chunkHandler,
                               Consumer<Throwable> errorHandler,
                               Runnable completionHandler) {
        streamResponse(
            messages,
            temperatureOverride,
            topPOverride,
            null,
            chunkHandler,
            errorHandler,
            completionHandler
        );
    }

    /**
     * 支持取消句柄的流式调用（对齐 ragent StreamCancellationHandle）。
     * cancelHandle 持有后，外部可随时调用 {@code cancelHandle.cancel()} 中断 LLM 读取。
     */
    @RagTraceNode(name = "llm-stream-chat", type = "LLM_CHAT")
    public void streamResponseWithHandle(List<Map<String, String>> messages,
                                         Double temperatureOverride,
                                         Double topPOverride,
                                         Integer maxTokensOverride,
                                         Consumer<String> chunkHandler,
                                         Consumer<Throwable> errorHandler,
                                         Runnable completionHandler,
                                         StreamCancellationHandle cancelHandle) {
        AtomicBoolean completionFlag = new AtomicBoolean(false);
        Double temperature = temperatureOverride != null ? temperatureOverride : temperature();
        Double topP = topPOverride != null ? topPOverride : topP();
        try {
            dashscopeClient.streamChatCompletion(
                messages,
                temperature,
                topP,
                maxTokens(maxTokensOverride),
                chunk -> {
                    if (chunkHandler == null) {
                        return;
                    }
                    String cleaned = sanitizeChunk(chunk);
                    if (hasMeaningfulText(cleaned)) {
                        chunkHandler.accept(cleaned);
                    }
                },
                cancelHandle
            );
        } catch (Exception e) {
            notifyError(errorHandler, e);
        } finally {
            notifyCompletion(completionFlag, completionHandler);
        }
    }

    private String buildUserPrompt(String userQuery, List<Map<String, String>> conversationHistory) {
        StringBuilder prompt = new StringBuilder();
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            prompt.append("对话历史：\n");
            for (Map<String, String> entry : conversationHistory) {
                if (entry == null) {
                    continue;
                }
                String role = entry.get("role");
                String content = entry.get("content");
                if (content == null || content.isBlank()) {
                    continue;
                }
                prompt.append("[").append(role == null ? "user" : role).append("] ")
                    .append(content).append('\n');
            }
            prompt.append('\n');
        }
        prompt.append(userQuery == null ? "" : userQuery);
        return prompt.toString();
    }

    private String buildSystemPrompt(String context) {
        LlmProperties.PromptTemplate template = llmProperties.getPromptTemplate();
        StringBuilder systemMessageBuilder = new StringBuilder();

        // 系统角色指令：从 prompts/system-rules.st 加载
        String rules = PromptTemplateLoader.load("system-rules.st");
        if (rules != null && !rules.isEmpty()) {
            systemMessageBuilder.append(rules).append("\n\n");
        }

        String refStart = template == null ? "<<参考资料开始>>" : getOrDefault(template.getRefStart(), "<<参考资料开始>>");
        String refEnd = template == null ? "<<参考资料结束>>" : getOrDefault(template.getRefEnd(), "<<参考资料结束>>");
        systemMessageBuilder.append(refStart).append("\n");

        if (context != null && !context.isEmpty()) {
            systemMessageBuilder.append(context);
        } else {
            String noResult = template == null
                ? "当前未检索到相关资料"
                : getOrDefault(template.getNoResultText(), "当前未检索到相关资料");
            systemMessageBuilder.append(noResult).append("\n");
        }

        systemMessageBuilder.append(refEnd);
        return systemMessageBuilder.toString();
    }

    public String systemRulesPrompt() {
        String rules = PromptTemplateLoader.load("system-rules.st");
        return rules == null ? "" : rules;
    }

    public List<Map<String, String>> toStructuredHistory(List<Map<String, String>> conversationHistory) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return messages;
        }
        for (Map<String, String> entry : conversationHistory) {
            if (entry == null) {
                continue;
            }
            String content = entry.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            String role = entry.get("role");
            if (!"user".equalsIgnoreCase(role) && !"assistant".equalsIgnoreCase(role)) {
                continue;
            }
            messages.add(Map.of(
                "role", role.toLowerCase(),
                "content", content
            ));
        }
        return messages;
    }

    private Double temperature() {
        LlmProperties.GenerationParams params = llmProperties.getGenerationParams();
        return params == null ? null : params.getTemperature();
    }

    private Double topP() {
        LlmProperties.GenerationParams params = llmProperties.getGenerationParams();
        return params == null ? null : params.getTopP();
    }

    private Integer maxTokens(Integer override) {
        if (override != null && override > 0) {
            return override;
        }
        LlmProperties.GenerationParams params = llmProperties.getGenerationParams();
        return params == null ? null : params.getMaxTokens();
    }

    private String getOrDefault(String value, String defaultValue) {
        return value != null ? value : defaultValue;
    }

    private void notifyError(Consumer<Throwable> errorHandler, Throwable throwable) {
        if (errorHandler != null) {
            errorHandler.accept(throwable);
        }
    }

    private void notifyCompletion(AtomicBoolean flag, Runnable completionHandler) {
        if (completionHandler != null && flag.compareAndSet(false, true)) {
            completionHandler.run();
        }
    }

    private static String sanitizeChunk(String rawChunk) {
        if (rawChunk == null || rawChunk.isBlank()) {
            return "";
        }
        return rawChunk
            .replace("<|im_start|>", "")
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .replace("\u0000", "")
            .replaceAll("(?i)^assistant\\s*", "")
            .replaceAll("(?i)^user\\s*", "");
    }

    private static boolean hasMeaningfulText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return MEANINGFUL_PATTERN.matcher(text).find();
    }
}
