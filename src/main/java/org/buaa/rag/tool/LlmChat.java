package org.buaa.rag.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.buaa.rag.properties.LlmProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 大语言模型聊天服务
 * 统一通过 Spring AI ChatClient 调用模型
 */
@Slf4j
@Service
public class LlmChat {
    private static final Pattern MEANINGFUL_PATTERN = Pattern.compile("[\\p{IsHan}\\p{L}\\p{N}]");
    private final LlmProperties llmProperties;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;

    public LlmChat(LlmProperties llmProperties,
                   ObjectProvider<ChatClient.Builder> chatClientBuilderProvider) {
        this.llmProperties = llmProperties;
        this.chatClientBuilderProvider = chatClientBuilderProvider;
    }

    public void streamResponse(String userQuery,
                               String referenceContext,
                               List<Map<String, String>> conversationHistory,
                               Consumer<String> chunkHandler,
                               Consumer<Throwable> errorHandler,
                               Runnable completionHandler) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            notifyError(errorHandler, new IllegalStateException("ChatClient 未配置"));
            notifyCompletion(new AtomicBoolean(false), completionHandler);
            return;
        }

        try {
            ChatClient.ChatClientRequestSpec request = builder.build()
                .prompt()
                .system(buildSystemPrompt(referenceContext))
                .options(buildChatOptions(null));

            List<Message> historyMessages = convertHistoryMessages(conversationHistory);
            if (!historyMessages.isEmpty()) {
                request = request.messages(historyMessages);
            }

            StreamChunkProbe streamProbe = new StreamChunkProbe();
            AtomicBoolean completionFlag = new AtomicBoolean(false);
            request.user(userQuery)
                .stream()
                .content()
                .doOnComplete(() -> {
                    String remaining = streamProbe.flush();
                    if (chunkHandler != null && remaining != null && !remaining.isEmpty()) {
                        chunkHandler.accept(remaining);
                    }
                    notifyCompletion(completionFlag, completionHandler);
                })
                .subscribe(
                    chunk -> {
                        if (chunkHandler == null || chunk == null || chunk.isEmpty()) {
                            return;
                        }
                        String cleaned = streamProbe.accept(chunk);
                        if (cleaned != null && !cleaned.isEmpty()) {
                            chunkHandler.accept(cleaned);
                        }
                    },
                    error -> {
                        notifyError(errorHandler, error);
                        notifyCompletion(completionFlag, completionHandler);
                    }
                );
        } catch (Exception e) {
            notifyError(errorHandler, e);
            notifyCompletion(new AtomicBoolean(false), completionHandler);
        }
    }

    public String generateCompletion(String systemPrompt,
                                     String userPrompt,
                                     Integer maxTokens) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            return "";
        }

        try {
            ChatClient.ChatClientRequestSpec request = builder.build()
                .prompt()
                .options(buildChatOptions(maxTokens));

            if (systemPrompt != null && !systemPrompt.isBlank()) {
                request = request.system(systemPrompt);
            }
            if (userPrompt != null && !userPrompt.isBlank()) {
                request = request.user(userPrompt);
            }

            String content = request.call().content();
            return content == null ? "" : content;
        } catch (Exception e) {
            log.warn("LLM 同步调用失败: {}", e.getMessage());
            return "";
        }
    }

    private ChatOptions buildChatOptions(Integer maxTokens) {
        LlmProperties.GenerationParams params = llmProperties.getGenerationParams();
        Integer resolvedMaxTokens = maxTokens;
        if (resolvedMaxTokens == null && params != null) {
            resolvedMaxTokens = params.getMaxTokens();
        }

        ChatOptions.Builder builder = ChatOptions.builder();
        if (params != null) {
            if (params.getTemperature() != null) {
                builder.temperature(params.getTemperature());
            }
            if (params.getTopP() != null) {
                builder.topP(params.getTopP());
            }
        }
        if (resolvedMaxTokens != null) {
            builder.maxTokens(resolvedMaxTokens);
        }
        return builder.build();
    }

    private List<Message> convertHistoryMessages(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        List<Message> messages = new ArrayList<>(history.size());
        for (Map<String, String> entry : history) {
            if (entry == null) {
                continue;
            }
            String role = entry.get("role");
            String content = entry.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            if ("assistant".equalsIgnoreCase(role)) {
                messages.add(new AssistantMessage(content));
            } else if ("user".equalsIgnoreCase(role)) {
                messages.add(new UserMessage(content));
            } else if ("system".equalsIgnoreCase(role)) {
                messages.add(new SystemMessage(content));
            }
        }
        return messages;
    }

    private String buildSystemPrompt(String context) {
        LlmProperties.PromptTemplate template = llmProperties.getPromptTemplate();
        StringBuilder systemMessageBuilder = new StringBuilder();

        if (template != null && template.getRules() != null && !template.getRules().isEmpty()) {
            systemMessageBuilder.append(template.getRules()).append("\n\n");
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

    private static final class StreamChunkProbe {
        private final StringBuilder buffer = new StringBuilder();
        private boolean firstPayloadSent;

        String accept(String rawChunk) {
            String cleaned = sanitizeChunk(rawChunk);
            if (cleaned.isEmpty()) {
                return "";
            }

            if (firstPayloadSent) {
                return cleaned;
            }

            buffer.append(cleaned);
            if (!hasMeaningfulText(buffer.toString())) {
                if (buffer.length() > 256) {
                    buffer.delete(0, buffer.length() - 64);
                }
                return "";
            }

            firstPayloadSent = true;
            String firstChunk = sanitizeChunk(buffer.toString());
            buffer.setLength(0);
            return firstChunk;
        }

        String flush() {
            if (firstPayloadSent || buffer.isEmpty()) {
                return "";
            }
            String candidate = sanitizeChunk(buffer.toString());
            if (!hasMeaningfulText(candidate)) {
                return "";
            }
            buffer.setLength(0);
            firstPayloadSent = true;
            return candidate;
        }
    }
}
