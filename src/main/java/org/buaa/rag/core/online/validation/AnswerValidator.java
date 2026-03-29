package org.buaa.rag.core.online.validation;

import org.buaa.rag.common.prompt.PromptTemplateLoader;
import org.buaa.rag.tool.LlmChat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AnswerValidator {

    public enum Verdict { OK, REFINE }

    private static final String VALIDATE_PROMPT = PromptTemplateLoader.load("answer-validator.st");

    private final LlmChat llmChat;

    public AnswerValidator(LlmChat llmChat) {
        this.llmChat = llmChat;
    }

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
