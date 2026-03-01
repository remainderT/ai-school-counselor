package org.buaa.rag.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import org.buaa.rag.dao.entity.MessageDO;
import org.buaa.rag.dao.mapper.MessageMapper;
import org.buaa.rag.dao.mapper.MessageSourceMapper;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.tool.LlmChat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationServiceImplTest {

    @Test
    void shouldAssembleSummaryAndRecentWindow() {
        MessageMapper messageMapper = mock(MessageMapper.class);
        MessageSourceMapper sourceMapper = mock(MessageSourceMapper.class);
        LlmChat llmChat = mock(LlmChat.class);

        RagProperties ragProperties = new RagProperties();
        ragProperties.getMemory().setHistoryKeepTurns(2);
        ragProperties.getMemory().setSummaryEnabled(true);
        ragProperties.getMemory().setSummaryStartTurns(2);
        ragProperties.getMemory().setSummaryMaxChars(120);
        ragProperties.getMemory().setSummaryMaxTokens(64);

        Executor sameThread = Runnable::run;

        when(messageMapper.selectList(any())).thenReturn(buildConversation(4));
        when(llmChat.generateCompletion(anyString(), anyString(), anyInt())).thenReturn("这是合并后的摘要");

        ConversationServiceImpl service = new ConversationServiceImpl(
            messageMapper,
            sourceMapper,
            llmChat,
            ragProperties,
            sameThread
        );

        List<Map<String, String>> context = service.loadConversationContext("s1");

        assertEquals(5, context.size());
        assertEquals("system", context.get(0).get("role"));
        String summaryContent = context.get(0).get("content");
        assertTrue(summaryContent.startsWith("历史会话摘要："));
        assertTrue(summaryContent.length() > "历史会话摘要：".length());
        assertEquals("user", context.get(1).get("role"));
    }

    @Test
    void shouldReturnOnlyRecentWindowWhenSummaryDisabled() {
        MessageMapper messageMapper = mock(MessageMapper.class);
        MessageSourceMapper sourceMapper = mock(MessageSourceMapper.class);
        LlmChat llmChat = mock(LlmChat.class);

        RagProperties ragProperties = new RagProperties();
        ragProperties.getMemory().setHistoryKeepTurns(2);
        ragProperties.getMemory().setSummaryEnabled(false);

        Executor sameThread = Runnable::run;

        when(messageMapper.selectList(any())).thenReturn(buildConversation(4));

        ConversationServiceImpl service = new ConversationServiceImpl(
            messageMapper,
            sourceMapper,
            llmChat,
            ragProperties,
            sameThread
        );

        List<Map<String, String>> context = service.loadConversationContext("s1");

        assertEquals(4, context.size());
        assertEquals("user", context.get(0).get("role"));
        assertEquals("assistant", context.get(1).get("role"));
    }

    private List<MessageDO> buildConversation(int turns) {
        List<MessageDO> messages = new ArrayList<>();
        for (int i = 1; i <= turns; i++) {
            MessageDO user = new MessageDO();
            user.setId((long) (i * 2 - 1));
            user.setSessionId("s1");
            user.setUserId("u1");
            user.setRole("user");
            user.setContent("用户问题" + i);
            user.setCreatedAt(LocalDateTime.now().minusMinutes((turns - i) * 2L + 1));
            messages.add(user);

            MessageDO assistant = new MessageDO();
            assistant.setId((long) (i * 2));
            assistant.setSessionId("s1");
            assistant.setUserId("u1");
            assistant.setRole("assistant");
            assistant.setContent("助手回答" + i);
            assistant.setCreatedAt(LocalDateTime.now().minusMinutes((turns - i) * 2L));
            messages.add(assistant);
        }
        return messages;
    }
}
