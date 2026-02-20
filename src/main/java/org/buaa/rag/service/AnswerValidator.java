package org.buaa.rag.service;

public interface AnswerValidator {

    enum Verdict { OK, REFINE }

    Verdict evaluate(String question, String answer);
}
