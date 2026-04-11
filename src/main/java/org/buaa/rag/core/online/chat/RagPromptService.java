package org.buaa.rag.core.online.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
        String referenceContext = constructReferenceContext(retrievalResults);
        // 根据场景选择温度：Tool 场景放宽、KB 场景收紧
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
                null,   // topP 使用配置默认值
                chunk -> {
                    responseBuilder.append(chunk);
                    if (chunkHandler != null) {
                        chunkHandler.accept(chunk);
                    }
                },
                streamError::set,
                () -> {
                }
        );
        Throwable err = streamError.get();
        if (err != null) {
            throw new RuntimeException("AI服务异常: " + err.getMessage(), err);
        }
        String rawResponse = responseBuilder.toString();
        String finalResponse = appendSourceReferences(rawResponse, retrievalResults);
        if (!finalResponse.equals(rawResponse)
                && finalResponse.startsWith(rawResponse)
                && chunkHandler != null) {
            chunkHandler.accept(finalResponse.substring(rawResponse.length()));
        }
        return finalResponse;
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
