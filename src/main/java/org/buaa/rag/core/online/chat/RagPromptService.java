package org.buaa.rag.core.online.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RAG Prompt 组装服务：负责参考上下文构建、Prompt 模板应用、来源引用附加和流式答案生成。
 *
 * <p>场景规划：
 * <ul>
 *   <li><b>KB 纯检索场景</b>：temperature 由 rag.prompt.temperature-kb 控制（确定性输出，避免幻觉）</li>
 *   <li><b>Tool 结果混合场景</b>：temperature 由 rag.prompt.temperature-tool 控制（工具结果已确定，允许适当推理扩展）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagPromptService {

    private static final String MULTI_INTENT_SYNTHESIS_PROMPT =
        PromptTemplateLoader.load("chat-multi-intent-synthesis.st");

    private final LlmChat llmService;
    private final RagProperties ragProperties;

    /**
     * 构建编号参考上下文块，注入到系统 Prompt 中。
     */
    public String constructReferenceContext(List<RetrievalMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            RetrievalMatch match = matches.get(i);
            String textSnippet = truncateText(match.getTextContent(), ragProperties.getPrompt().getMaxReferenceLength());
            String sourceLabel = getSourceLabel(match);
            contextBuilder.append(String.format("[%d] (%s) %s\n", i + 1, sourceLabel, textSnippet));
        }
        return contextBuilder.toString();
    }

    /**
     * 在回复末尾附加格式化的参考来源引用块（幂等：已有「参考来源：」则不重复添加）。
     */
    public String appendSourceReferences(String response, List<RetrievalMatch> sources) {
        String safeResponse = response == null ? "" : response;
        if (sources == null || sources.isEmpty()) {
            return safeResponse;
        }
        if (safeResponse.contains("参考来源：")) {
            return safeResponse;
        }

        List<RetrievalMatch> deduplicated = deduplicateForDisplay(sources);
        if (deduplicated.isEmpty()) {
            return safeResponse;
        }

        StringBuilder builder = new StringBuilder(safeResponse);
        if (!safeResponse.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append("参考来源：\n");

        int max = Math.min(ragProperties.getPrompt().getMaxSourceReferenceCount(), deduplicated.size());
        for (int i = 0; i < max; i++) {
            RetrievalMatch source = deduplicated.get(i);
            builder.append("[").append(i + 1).append("] ").append(getSourceLabel(source));
            if (source.getChunkId() != null) {
                builder.append("（片段#").append(source.getChunkId()).append("）");
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    /**
     * 完整 RAG 答案生成：组装上下文 → 调用 LLM 流式输出 → 附加来源引用。
     * 使用 KB 纯检索场景温度（rag.prompt.temperature-kb）。
     */
    public String generateRagAnswer(String query,
                                    String promptTemplate,
                                    List<Map<String, String>> conversationHistory,
                                    List<RetrievalMatch> retrievalResults,
                                    Consumer<String> chunkHandler) {
        return generateRagAnswer(query, promptTemplate, conversationHistory,
            retrievalResults, chunkHandler, false);
    }

    /**
     * 场景感知的完整 RAG 答案生成。
     *
     * @param hasToolContext true 表示上下文包含 Tool 调用结果（使用较高温度），
     *                       false 表示纯 KB 检索场景（使用低温度）
     */
    public String generateRagAnswer(String query,
                                    String promptTemplate,
                                    List<Map<String, String>> conversationHistory,
                                    List<RetrievalMatch> retrievalResults,
                                    Consumer<String> chunkHandler,
                                    boolean hasToolContext) {
        String rawResponse = generateRagAnswerWithoutReferences(
            query, promptTemplate, conversationHistory, retrievalResults, hasToolContext);
        String finalResponse = appendSourceReferences(rawResponse, retrievalResults);
        if (!finalResponse.equals(rawResponse)
                && finalResponse.startsWith(rawResponse)
                && chunkHandler != null) {
            chunkHandler.accept(finalResponse.substring(rawResponse.length()));
        }
        return finalResponse;
    }

    /**
     * 仅生成正文，不自动追加「参考来源」区块。
     * 适用于多意图场景先产出子答案，再统一做最终合成。
     */
    public String generateRagAnswerWithoutReferences(String query,
                                                     String promptTemplate,
                                                     List<Map<String, String>> conversationHistory,
                                                     List<RetrievalMatch> retrievalResults,
                                                     boolean hasToolContext) {
        String referenceContext = constructReferenceContext(retrievalResults);
        RagProperties.Prompt promptConfig = ragProperties.getPrompt();
        double temperature = hasToolContext ? promptConfig.getTemperatureTool() : promptConfig.getTemperatureKb();

        StringBuilder responseBuilder = new StringBuilder();
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        String appliedPrompt = (promptTemplate == null || promptTemplate.isBlank())
            ? referenceContext : promptTemplate + "\n" + referenceContext;
        llmService.streamResponse(
            query,
            appliedPrompt,
            conversationHistory,
            temperature,
            null,
            responseBuilder::append,
            streamError::set,
            () -> {
            }
        );
        Throwable err = streamError.get();
        if (err != null) {
            throw new RuntimeException("AI服务异常: " + err.getMessage(), err);
        }
        return responseBuilder.toString();
    }

    /**
     * 多意图最终合成：基于子问题独立结论，生成一段结构化总答复。
     */
    public String synthesizeMultiIntentAnswer(String originalQuery, List<String> subResults) {
        if (subResults == null || subResults.isEmpty()) {
            return "";
        }
        String systemPrompt = StringUtils.hasText(MULTI_INTENT_SYNTHESIS_PROMPT)
            ? MULTI_INTENT_SYNTHESIS_PROMPT
            : "请根据子问题结果给出综合回答，先结论后步骤。";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("原始用户问题：\n")
            .append(originalQuery == null ? "" : originalQuery)
            .append("\n\n子问题处理结果：\n");
        for (int i = 0; i < subResults.size(); i++) {
            userPrompt.append("【结果").append(i + 1).append("】\n")
                .append(subResults.get(i))
                .append("\n\n");
        }

        String synthesized = llmService.generateCompletion(systemPrompt, userPrompt.toString(), 1200);
        if (StringUtils.hasText(synthesized)) {
            return synthesized.trim();
        }
        // 同步 LLM 异常/空输出时回退到可读拼接文本，避免前端空白
        return String.join("\n\n", subResults);
    }

    // ──────────────────────── private ────────────────────────

    private List<RetrievalMatch> deduplicateForDisplay(List<RetrievalMatch> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        Map<String, RetrievalMatch> map = new HashMap<>();
        for (RetrievalMatch source : sources) {
            if (source == null) {
                continue;
            }
            String md5 = source.getFileMd5() != null ? source.getFileMd5() : "unknown";
            String chunk = source.getChunkId() != null ? source.getChunkId().toString() : "0";
            String key = md5 + ":" + chunk;
            map.putIfAbsent(key, source);
        }
        List<RetrievalMatch> deduplicated = new ArrayList<>(map.values());
        deduplicated.sort((a, b) -> Double.compare(
                b.getRelevanceScore() != null ? b.getRelevanceScore() : 0.0,
                a.getRelevanceScore() != null ? a.getRelevanceScore() : 0.0
        ));
        return deduplicated;
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…";
    }

    private String getSourceLabel(RetrievalMatch match) {
        return StringUtils.hasText(match.getSourceFileName()) ? match.getSourceFileName() : "未知来源";
    }
}
