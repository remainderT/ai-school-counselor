package org.buaa.rag.core.online.intent;

import java.util.List;

import org.buaa.rag.properties.IntentGuidanceProperties;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 歧义二次确认服务：当意图候选分数比值落在"边界区间"时，
 * 通过一次轻量 LLM 调用判断用户问题是否真正歧义，减少误触发澄清。
 *
 * <h3>触发条件</h3>
 * <ol>
 *   <li>候选数 ≥ 2</li>
 *   <li>次高分 / 最高分 ≥ {@code ambiguityRatio}（候选相近，有歧义可能）</li>
 *   <li>比值 &lt; {@code ambiguityRatioUpperBound}（还没有明显到不需要判断）</li>
 * </ol>
 * 边界区间之外的情况：直接走原有规则（高比值=确实歧义，低比值=不歧义），不调 LLM。
 *
 * <h3>LLM 调用语义</h3>
 * 输入：用户问题 + 两个候选域名<br>
 * 输出：单个字符 {@code "Y"} 或 {@code "N"}（表示"是否歧义"）
 */
@Slf4j
@Service
public class AmbiguityLLMChecker {

    /**
     * 边界区间上界：比值超过此值视为"非常相近"，直接判歧义无需 LLM 确认。
     * 低于 {@code ambiguityRatio} 则明确不歧义。
     */
    private static final double AMBIGUITY_UPPER_BOUND = 0.97;

    private static final double CLASSIFY_TEMPERATURE = 0.0;
    private static final double CLASSIFY_TOP_P = 1.0;
    private static final int MAX_TOKENS = 8;

    private static final String SYSTEM_PROMPT =
        "你是一个意图歧义判断助手。" +
        "我会给你一个用户问题和两个候选分类名称，" +
        "请判断用户的问题是否在这两个分类之间存在真实歧义（即无法从问题本身推断用户更倾向哪一个）。" +
        "只回复单个字母：Y（存在歧义）或 N（无歧义/意图明确）。";

    private final LlmChat llmChat;
    private final IntentGuidanceProperties guidanceProperties;

    public AmbiguityLLMChecker(LlmChat llmChat, IntentGuidanceProperties guidanceProperties) {
        this.llmChat = llmChat;
        this.guidanceProperties = guidanceProperties;
    }

    /**
     * 判断候选列表是否需要引导：
     * <ul>
     *   <li>比值低于下界 → 不歧义（快速返回 false）</li>
     *   <li>比值高于上界 → 确定歧义（快速返回 true）</li>
     *   <li>边界区间内 → LLM 二次确认</li>
     * </ul>
     *
     * @param query      用户问题
     * @param candidates 意图候选列表，至少 2 个
     * @param topScore   最高分候选的分数
     * @param secondScore 次高分候选的分数
     * @param options    引导选项名称列表（用于提示 LLM）
     * @return {@code true} 表示存在歧义，应触发澄清引导
     */
    public boolean isAmbiguous(String query,
                                List<String> options,
                                double topScore,
                                double secondScore) {
        if (!guidanceProperties.isEnabled()) {
            return false;
        }
        if (topScore <= 0 || secondScore <= 0 || options == null || options.size() < 2) {
            return false;
        }

        double ratio = secondScore / topScore;
        double lowerBound = guidanceProperties.getAmbiguityRatio();

        // 比值低于下界：最高分候选明显胜出，不需要歧义引导
        if (ratio < lowerBound) {
            return false;
        }

        // 比值高于上界：两候选极度相近，直接认为歧义
        if (ratio >= AMBIGUITY_UPPER_BOUND) {
            log.debug("歧义二次确认：比值={} ≥ 上界{}，直接判歧义", ratio, AMBIGUITY_UPPER_BOUND);
            return true;
        }

        // 边界区间 [lowerBound, AMBIGUITY_UPPER_BOUND)：调 LLM 确认
        return checkWithLlm(query, options.get(0), options.get(1));
    }

    /**
     * 调用 LLM 做单次歧义确认，返回 true 表示存在歧义。
     * 失败时保守降级为"不歧义"（避免误触发澄清）。
     */
    private boolean checkWithLlm(String query, String optionA, String optionB) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        String userPrompt = String.format(
            "用户问题：%s\n候选分类A：%s\n候选分类B：%s\n是否歧义（Y/N）：",
            query.trim(), optionA, optionB
        );
        try {
            String raw = llmChat.generateCompletion(
                SYSTEM_PROMPT, userPrompt, MAX_TOKENS, CLASSIFY_TEMPERATURE, CLASSIFY_TOP_P);
            if (!StringUtils.hasText(raw)) {
                log.debug("歧义LLM二次确认无输出，降级为不歧义");
                return false;
            }
            String answer = raw.trim().toUpperCase();
            boolean ambiguous = answer.startsWith("Y");
            log.debug("歧义LLM二次确认 | query='{}' | optionA='{}' | optionB='{}' | result={}",
                compact(query), optionA, optionB, ambiguous ? "歧义" : "不歧义");
            return ambiguous;
        } catch (Exception e) {
            log.debug("歧义LLM二次确认异常，降级为不歧义: {}", e.getMessage());
            return false;
        }
    }

    private String compact(String text) {
        if (text == null) return "";
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
