package org.buaa.rag.service.impl;

import org.buaa.rag.config.RagConfiguration;
import org.buaa.rag.tool.LlmChat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryDecomposerImplTest {

    @Test
    void shouldSplitScoreAndScholarshipQuestionByRules() {
        LlmChat llmPort = mock(LlmChat.class);
        when(llmPort.generateCompletion(anyString(), anyString(), anyInt())).thenReturn("");

        RagConfiguration config = new RagConfiguration();
        config.getDecomposition().setEnabled(true);
        config.getDecomposition().setMaxSubqueries(3);

        QueryDecomposerImpl decomposer = new QueryDecomposerImpl(llmPort, config);
        List<String> result = decomposer.decompose("我挂了一门选修，会影响我申请国家奖学金吗");

        assertEquals(2, result.size());
        assertTrue(result.get(0).contains("挂科"));
        assertTrue(result.get(1).contains("国家奖学金"));
    }
}
