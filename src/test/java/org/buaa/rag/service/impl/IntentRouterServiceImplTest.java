package org.buaa.rag.service.impl;

import org.buaa.rag.dto.IntentDecision;
import org.buaa.rag.tool.VectorEncoding;
import org.buaa.rag.tool.LlmChat;
import org.buaa.rag.service.IntentPatternService;
import org.buaa.rag.service.IntentTreeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentRouterServiceImplTest {

    private LlmChat llmPort;
    private IntentPatternService intentPatternService;
    private IntentTreeService intentTreeService;
    private VectorEncoding embeddingPort;
    private ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private IntentRouterServiceImpl intentRouterService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        llmPort = mock(LlmChat.class);
        intentPatternService = mock(IntentPatternService.class);
        intentTreeService = mock(IntentTreeService.class);
        embeddingPort = mock(VectorEncoding.class);
        chatClientBuilderProvider = mock(ObjectProvider.class);

        doNothing().when(intentTreeService).loadTree();
        when(intentTreeService.root()).thenReturn(Optional.empty());
        when(intentPatternService.semanticRoute(anyString())).thenReturn(Optional.empty());
        when(chatClientBuilderProvider.getIfAvailable()).thenReturn(null);
        when(llmPort.generateCompletion(anyString(), anyString(), anyInt())).thenReturn("");

        intentRouterService = new IntentRouterServiceImpl(
            llmPort,
            intentPatternService,
            intentTreeService,
            embeddingPort,
            chatClientBuilderProvider
        );
    }

    @Test
    void shouldRouteToolForLeaveKeyword() {
        IntentDecision decision = intentRouterService.decide("u1", "怎么请假");

        assertEquals(IntentDecision.Action.ROUTE_TOOL, decision.getAction());
        assertEquals("leave", decision.getToolName());
        assertTrue(decision.getConfidence() >= 0.9);
    }

    @Test
    void shouldRouteRagForHighConfidencePolicyQuery() {
        IntentDecision semanticHit = IntentDecision.builder()
            .action(IntentDecision.Action.ROUTE_RAG)
            .level1("教务教学")
            .level2("保研政策")
            .confidence(0.95)
            .build();
        when(intentPatternService.semanticRoute("计算机学院保研条件"))
            .thenReturn(Optional.of(semanticHit));

        IntentDecision decision = intentRouterService.decide("u1", "计算机学院保研条件");

        assertEquals(IntentDecision.Action.ROUTE_RAG, decision.getAction());
        assertEquals("保研政策", decision.getLevel2());
        assertTrue(decision.getConfidence() >= 0.9);
    }

    @Test
    void shouldInterceptCrisis() {
        IntentDecision decision = intentRouterService.decide("u1", "我不想活了");

        assertEquals(IntentDecision.Action.CRISIS, decision.getAction());
        assertNotNull(decision.getClarifyQuestion());
    }

    @Test
    void shouldClarifyAmbiguousScholarshipQuestion() {
        String llmJson = """
            {
              "level1":"学生事务与奖助",
              "level2":"奖学金",
              "confidence":0.3,
              "toolName":"none",
              "clarify":"同学，你想了解国奖、校奖还是社会奖学金？"
            }
            """;
        when(llmPort.generateCompletion(anyString(), anyString(), anyInt())).thenReturn(llmJson);

        IntentDecision decision = intentRouterService.decide("u1", "奖学金怎么申请");

        assertEquals(IntentDecision.Action.CLARIFY, decision.getAction());
        assertTrue(decision.getClarifyQuestion().contains("国奖"));
    }
}
