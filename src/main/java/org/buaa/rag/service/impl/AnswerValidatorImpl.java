package org.buaa.rag.service.impl;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.service.AnswerValidator;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AnswerValidatorImpl implements AnswerValidator {

    private static final String VALIDATE_PROMPT = PromptTemplateLoader.load("answer-validator.st", """
你是答案质检员。阅读用户问题和回答，判断回答是否充分且基于资料。
只输出 OK 或 REFINE。
""");

    private final LlmChat llmChat;

    public AnswerValidatorImpl(LlmChat llmChat) {
        this.llmChat = llmChat;
    }

    @Override
    public Verdict evaluate(String question, String answer) {
        if (!StringUtils.hasText(answer)) {
            return Verdict.REFINE;
        }
        String userPrompt = "问题：" + question + "\n回答：" + answer;
        String result = llmChat.generateCompletion(VALIDATE_PROMPT, userPrompt, 32);
        if (result == null) {
            return Verdict.OK;
        }
        result = result.trim().toUpperCase();
        return result.contains("REFINE") ? Verdict.REFINE : Verdict.OK;
    }
}
