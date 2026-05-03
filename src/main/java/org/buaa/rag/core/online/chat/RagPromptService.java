package org.buaa.rag.core.online.chat;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.online.retrieval.SubQueryRetrievalResult;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.Nullable;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.Builder;
import lombok.Getter;

/**
 * RAG Prompt 组装服务：负责参考上下文构建、Prompt 模板应用和流式答案生成。
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
    /** 单意图 RAG 场景默认系统提示（由模板文件驱动） */
    private static final String SINGLE_INTENT_DEFAULT_PROMPT =
        PromptTemplateLoader.load("rag-single-intent.st");
    /** 多意图综合场景默认系统提示 */
    private static final String MULTI_INTENT_DEFAULT_PROMPT =
        PromptTemplateLoader.load("rag-multi-intent.st");
    /** 单个文档块模板：对齐 context-format.st sub-question-kb-wrapper section */
    private static final String DOC_BLOCK_TPL =
        "<document index=\"%d\">\n<question>%s</question>\n<content>%s</content>\n</document>";
    /** 动态结果片段（工具结果）标头，保留给 tool context 场景 */
    private static final String TOOL_CONTEXT_HEADER = "## 动态结果片段";

    private final LlmChat llmService;
    private final RagProperties ragProperties;

    public List<RetrievalMatch> limitSourcesForAnswer(List<RetrievalMatch> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        int max = Math.max(1, ragProperties.getPrompt().getMaxSourceReferenceCount());
        if (sources.size() <= max) {
            return List.copyOf(sources);
        }
        return List.copyOf(sources.subList(0, max));
    }

    private String constructReferenceContext(List<RetrievalMatch> matches) {
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
     * 仅生成正文，不自动追加「参考来源」区块。
     * 适用于多意图场景先产出子答案，再统一做最终合成。
     */
    public String generateRagAnswerWithoutReferences(String query,
                                                     String promptTemplate,
                                                     List<Map<String, String>> conversationHistory,
                                                     List<RetrievalMatch> retrievalResults,
                                                     boolean hasToolContext) {
        long start = System.nanoTime();
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
        log.info("RAG答案生成完成 | query='{}' | refs={} | hasToolContext={} | responseChars={} | 耗时={}ms",
            compact(query), retrievalResults == null ? 0 : retrievalResults.size(), hasToolContext,
            responseBuilder.length(), elapsedMs(start));
        return responseBuilder.toString();
    }

    public String generateMultiIntentAnswer(String originalQuery,
                                           List<Map<String, String>> conversationHistory,
                                           List<SubQueryRetrievalResult> subQueryResults,
                                           Consumer<String> chunkHandler) {
        return generateMultiIntentAnswer(originalQuery, conversationHistory, subQueryResults, chunkHandler, null);
    }

    public String generateMultiIntentAnswer(String originalQuery,
                                           List<Map<String, String>> conversationHistory,
                                           List<SubQueryRetrievalResult> subQueryResults,
                                           Consumer<String> chunkHandler,
                                           @Nullable StreamCancellationHandle cancelHandle) {
        long start = System.nanoTime();
        if (subQueryResults == null || subQueryResults.isEmpty()) {
            return "";
        }
        int mergedReferences = subQueryResults.stream()
            .mapToInt(result -> result.sources() == null ? 0 : result.sources().size())
            .sum();
        PromptContext promptContext = PromptContext.builder()
            .question(originalQuery)
            .kbContext(buildMultiIntentDocumentBlock(subQueryResults))
            .toolContext(buildMultiIntentDynamicBlock(subQueryResults))
            .preferredPrompt(MULTI_INTENT_SYNTHESIS_PROMPT)
            .promptSnippet(buildMultiIntentRuleBlock(subQueryResults))
            .subQuestions(subQueryResults.stream().map(SubQueryRetrievalResult::query).toList())
            .build();
        boolean hasToolContext = promptContext.hasTool();
        List<Map<String, String>> messages = buildMultiIntentMessages(
            promptContext, conversationHistory, subQueryResults);
        String answer = streamStructuredAnswer(messages, chunkHandler, hasToolContext, cancelHandle);
        log.info("多意图综合答案生成完成 | query='{}' | subQueries={} | refs={} | responseChars={} | 耗时={}ms",
            compact(originalQuery), subQueryResults.size(), mergedReferences, answer.length(), elapsedMs(start));
        return answer;
    }

    public String generateSingleIntentStructuredAnswer(String query,
                                                       List<Map<String, String>> conversationHistory,
                                                       List<RetrievalMatch> retrievalResults,
                                                       IntentPromptDescriptor intentPrompt,
                                                       Consumer<String> chunkHandler,
                                                       boolean hasToolContext) {
        return generateSingleIntentStructuredAnswer(
            query, conversationHistory, retrievalResults, intentPrompt, chunkHandler, hasToolContext, null);
    }

    public String generateSingleIntentStructuredAnswer(String query,
                                                       List<Map<String, String>> conversationHistory,
                                                       List<RetrievalMatch> retrievalResults,
                                                       IntentPromptDescriptor intentPrompt,
                                                       Consumer<String> chunkHandler,
                                                       boolean hasToolContext,
                                                       @Nullable StreamCancellationHandle cancelHandle) {
        long start = System.nanoTime();
        PromptContext promptContext = PromptContext.builder()
            .question(query)
            .kbContext(buildSingleIntentDocumentBlock(retrievalResults, query))
            .preferredPrompt(intentPrompt != null ? intentPrompt.promptTemplate() : null)
            .promptSnippet(intentPrompt != null ? intentPrompt.promptSnippet() : null)
            .subQuestions(List.of(query))
            .build();
        List<Map<String, String>> messages = buildSingleIntentMessages(
            promptContext, conversationHistory, intentPrompt);
        String answer = streamStructuredAnswer(messages, chunkHandler, hasToolContext, cancelHandle);
        log.info("结构化单意图答案生成完成 | query='{}' | refs={} | hasToolContext={} | responseChars={} | 耗时={}ms",
            compact(query), retrievalResults == null ? 0 : retrievalResults.size(), hasToolContext, answer.length(), elapsedMs(start));
        return answer;
    }

    public List<RetrievalMatch> collectDisplayedMultiIntentSources(List<SubQueryRetrievalResult> subQueryResults) {
        if (subQueryResults == null || subQueryResults.isEmpty()) {
            return List.of();
        }
        int perQuestionLimit = Math.max(1, Math.min(
            ragProperties.getPrompt().getMultiIntentSourcesPerQuestion(),
            ragProperties.getPrompt().getMaxSourceReferenceCount()
        ));
        int globalLimit = Math.max(
            perQuestionLimit,
            Math.min(
                ragProperties.getPrompt().getMaxSourceReferenceCount(),
                subQueryResults.size() * perQuestionLimit
            )
        );

        List<RetrievalMatch> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SubQueryRetrievalResult result : subQueryResults) {
            List<RetrievalMatch> sources = result.sources();
            if (sources == null || sources.isEmpty()) {
                continue;
            }
            int addedForQuestion = 0;
            for (RetrievalMatch match : sources) {
                if (match == null) {
                    continue;
                }
                if (!seen.add(match.matchKey())) {
                    continue;
                }
                merged.add(match);
                addedForQuestion++;
                if (addedForQuestion >= perQuestionLimit || merged.size() >= globalLimit) {
                    break;
                }
            }
            if (merged.size() >= globalLimit) {
                break;
            }
        }
        return merged;
    }

    // ──────────────────────── private ────────────────────────

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

    private List<Map<String, String>> buildSingleIntentMessages(PromptContext promptContext,
                                                                List<Map<String, String>> conversationHistory,
                                                                IntentPromptDescriptor intentPrompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
            buildSystemPrompt(promptContext, SINGLE_INTENT_DEFAULT_PROMPT)));

        if (intentPrompt != null && !isBlank(intentPrompt.label())) {
            messages.add(Map.of("role", "system", "content", "## 当前事项\n" + intentPrompt.label().trim()));
        }

        if (promptContext.hasKb()) {
            messages.add(Map.of("role", "user", "content", promptContext.getKbContext()));
        }

        messages.addAll(llmService.toStructuredHistory(conversationHistory));
        if (!isBlank(promptContext.getQuestion())) {
            // 用 XML <question> 标签包装，对齐 context-format.st single-question section
            messages.add(Map.of("role", "user", "content",
                "<question>" + promptContext.getQuestion().trim() + "</question>"));
        }
        return messages;
    }

    private List<Map<String, String>> buildMultiIntentMessages(PromptContext promptContext,
                                                               List<Map<String, String>> conversationHistory,
                                                               List<SubQueryRetrievalResult> subQueryResults) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
            buildSystemPrompt(promptContext, MULTI_INTENT_DEFAULT_PROMPT)));

        if (promptContext.hasTool()) {
            messages.add(Map.of("role", "system", "content", promptContext.getToolContext()));
        }
        if (promptContext.hasKb()) {
            messages.add(Map.of("role", "user", "content", promptContext.getKbContext()));
        }

        if (ragProperties.getPrompt().isIncludeHistoryInMultiIntent()) {
            messages.addAll(llmService.toStructuredHistory(conversationHistory));
        }
        messages.add(Map.of("role", "user", "content", buildMultiIntentUserQuery(promptContext.getQuestion(), subQueryResults)));
        return messages;
    }

    private String buildMultiIntentRuleBlock(List<SubQueryRetrievalResult> subQueryResults) {
        List<String> ruleLines = new ArrayList<>();
        for (SubQueryRetrievalResult result : subQueryResults) {
            String rule = extractIntentRule(result);
            if (!isBlank(rule) && !ruleLines.contains(rule)) {
                ruleLines.add(rule);
            }
        }
        if (ruleLines.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("## 回答规则\n");
        for (int i = 0; i < ruleLines.size(); i++) {
            builder.append(i + 1).append(". ").append(ruleLines.get(i)).append("\n");
        }
        builder.append(ruleLines.size() + 1)
            .append(". 回答尽量精炼，优先覆盖全部事项，再补充关键流程、条件和时间节点。\n");
        return builder.toString().trim();
    }

    private String buildMultiIntentDynamicBlock(List<SubQueryRetrievalResult> subQueryResults) {
        StringBuilder prompt = new StringBuilder();
        boolean hasContent = false;
        prompt.append(TOOL_CONTEXT_HEADER).append("\n");
        for (int i = 0; i < subQueryResults.size(); i++) {
            SubQueryRetrievalResult result = subQueryResults.get(i);
            if (!isBlank(result.directResponse())) {
                hasContent = true;
                prompt.append("### ").append(i + 1).append(". ").append(result.query()).append("\n");
                prompt.append(result.directResponse()).append("\n\n");
            } else if (result.clarifyTriggered() && !isBlank(result.clarifyMessage())) {
                hasContent = true;
                prompt.append("### ").append(i + 1).append(". ").append(result.query()).append("\n");
                prompt.append("资料不足：").append(result.clarifyMessage()).append("\n\n");
            }
        }
        if (!hasContent) {
            return "";
        }
        return prompt.toString().trim();
    }

    private String buildSingleIntentDocumentBlock(List<RetrievalMatch> retrievalResults) {
        return buildSingleIntentDocumentBlock(retrievalResults, null);
    }

    private String buildSingleIntentDocumentBlock(List<RetrievalMatch> retrievalResults, String question) {
        if (retrievalResults == null || retrievalResults.isEmpty()) {
            return "";
        }
        String q = isBlank(question) ? "用户问题" : question;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < retrievalResults.size(); i++) {
            RetrievalMatch match = retrievalResults.get(i);
            String text = truncateText(match.getTextContent(), ragProperties.getPrompt().getMaxReferenceLength());
            body.append(String.format(DOC_BLOCK_TPL, i + 1, q, text)).append("\n");
        }
        return "<documents>\n" + body.toString().stripTrailing() + "\n</documents>";
    }

    private String buildMultiIntentDocumentBlock(List<SubQueryRetrievalResult> subQueryResults) {
        if (subQueryResults == null || subQueryResults.isEmpty()) {
            return "";
        }
        int snippetLen = Math.max(60, Math.min(
            ragProperties.getPrompt().getMultiIntentSnippetLength(),
            ragProperties.getPrompt().getMaxReferenceLength()
        ));
        int perQuestionLimit = Math.max(1, Math.min(
            ragProperties.getPrompt().getMultiIntentSourcesPerQuestion(),
            ragProperties.getPrompt().getMaxSourceReferenceCount()
        ));

        StringBuilder body = new StringBuilder();
        int globalIdx = 1;
        boolean anyContent = false;
        for (SubQueryRetrievalResult result : subQueryResults) {
            List<RetrievalMatch> sources = result.sources();
            if (sources == null || sources.isEmpty()) {
                // 子问题无检索结果：输出空 document 块，让模型知道该子问题无资料
                body.append(String.format(DOC_BLOCK_TPL, globalIdx++,
                    result.query(), "（暂无相关文档）")).append("\n");
                continue;
            }
            anyContent = true;
            int added = 0;
            for (RetrievalMatch match : sources) {
                if (match == null || added >= perQuestionLimit) break;
                String text = truncateText(match.getTextContent(), snippetLen);
                body.append(String.format(DOC_BLOCK_TPL, globalIdx++, result.query(), text)).append("\n");
                added++;
            }
        }
        if (!anyContent) {
            return "";
        }
        return "<documents>\n" + body.toString().stripTrailing() + "\n</documents>";
    }

    private String buildMultiIntentUserQuery(String originalQuery, List<SubQueryRetrievalResult> subQueryResults) {
        // 用 XML <questions> 块包装，对齐 context-format.st multi-questions section
        StringBuilder questions = new StringBuilder();
        for (int i = 0; i < subQueryResults.size(); i++) {
            questions.append(i + 1).append(". ").append(subQueryResults.get(i).query()).append("\n");
        }
        return "原始问题：" + (originalQuery == null ? "" : originalQuery)
            + "\n\n<questions>\n" + questions.toString().stripTrailing() + "\n</questions>"
            + "\n\n请结合 <documents> 内的资料，按顺序完整回答以上各事项。";
    }

    /**
     * 构建系统提示词。
     * <p>参考 ragent 的 {@code RAGPromptService.planPrompt} 设计：
     * <ul>
     *   <li>单意图且有自定义 prompt → 优先使用意图节点的 promptTemplate</li>
     *   <li>否则 → 使用默认的 single/multi intent 模板</li>
     *   <li>意图级 promptSnippet 始终追加（补充约束规则）</li>
     * </ul>
     */
    private String buildSystemPrompt(PromptContext context, String fallbackPrompt) {
        StringBuilder builder = new StringBuilder();
        String rules = llmService.systemRulesPrompt();
        if (StringUtils.hasText(rules)) {
            builder.append(rules.trim()).append("\n\n");
        }

        // 场景感知模板选择：优先自定义 → 默认模板
        if (context != null && StringUtils.hasText(context.getPreferredPrompt())) {
            builder.append(context.getPreferredPrompt().trim()).append("\n\n");
        } else if (StringUtils.hasText(fallbackPrompt)) {
            builder.append(fallbackPrompt.trim()).append("\n\n");
        }

        // 意图级回答规则补充（promptSnippet 承载细粒度约束）
        if (context != null && StringUtils.hasText(context.getPromptSnippet())) {
            builder.append("## 回答补充约束\n").append(context.getPromptSnippet().trim()).append("\n\n");
        }

        // 场景温度提示：纯KB场景强调精确性，Tool场景允许适度推理
        if (context != null && context.hasTool()) {
            builder.append("> 本次回答涉及工具/动态数据，可在工具结果基础上做适当推理和汇总。\n\n");
        }

        return builder.toString().trim();
    }

    private String streamStructuredAnswer(List<Map<String, String>> messages,
                                          Consumer<String> chunkHandler,
                                          boolean hasToolContext) {
        return streamStructuredAnswer(messages, chunkHandler, hasToolContext, null);
    }

    /**
     * 执行流式结构化答案生成，支持 LLM 层取消句柄。
     *
     * @param cancelHandle 可选的取消句柄；非 null 时，LLM 层会在每个 chunk 读取后检查取消状态
     */
    private String streamStructuredAnswer(List<Map<String, String>> messages,
                                          Consumer<String> chunkHandler,
                                          boolean hasToolContext,
                                          @Nullable StreamCancellationHandle cancelHandle) {
        long start = System.nanoTime();
        RagProperties.Prompt promptConfig = ragProperties.getPrompt();
        double temperature = hasToolContext ? promptConfig.getTemperatureTool() : promptConfig.getTemperatureKb();
        double topP = hasToolContext ? promptConfig.getTopPTool() : promptConfig.getTopPKb();
        int maxTokens = hasToolContext ? promptConfig.getMultiIntentMaxTokens() : promptConfig.getSingleIntentMaxTokens();
        StringBuilder responseBuilder = new StringBuilder();
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        llmService.streamResponseWithHandle(
            messages,
            temperature,
            topP,
            maxTokens,
            chunk -> {
                responseBuilder.append(chunk);
                if (chunkHandler != null) {
                    chunkHandler.accept(chunk);
                }
            },
            streamError::set,
            () -> {
            },
            cancelHandle
        );
        Throwable err = streamError.get();
        if (err != null) {
            throw new RuntimeException("AI服务异常: " + err.getMessage(), err);
        }
        log.info("结构化答案生成完成 | messages={} | responseChars={} | hasToolContext={} | cancelled={} | 耗时={}ms",
            messages == null ? 0 : messages.size(), responseBuilder.length(), hasToolContext,
            cancelHandle != null && cancelHandle.isCancelled(), elapsedMs(start));
        return responseBuilder.toString();
    }

    private String compact(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private String extractIntentRule(SubQueryRetrievalResult result) {
        if (result == null || result.intent() == null) {
            return "";
        }
        if (!isBlank(result.intent().getPromptSnippet())) {
            return result.intent().getPromptSnippet().trim();
        }
        if (!isBlank(result.intent().getPromptTemplate())) {
            return result.intent().getPromptTemplate().trim();
        }
        if (!isBlank(result.intent().getLevel2())) {
            return "优先围绕“" + result.intent().getLevel2().trim() + "”事项回答。";
        }
        return "";
    }

    public record IntentPromptDescriptor(String label, String promptTemplate, String promptSnippet) {
    }

    @Getter
    @Builder
    private static class PromptContext {

        private final String question;
        private final String kbContext;
        private final String toolContext;
        private final String preferredPrompt;
        private final String promptSnippet;
        private final List<String> subQuestions;

        private boolean hasKb() {
            return kbContext != null && !kbContext.isBlank();
        }

        private boolean hasTool() {
            return toolContext != null && !toolContext.isBlank();
        }

        private boolean isMultiIntent() {
            return subQuestions != null && subQuestions.size() > 1;
        }
    }
}
