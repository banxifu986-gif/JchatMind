package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindMemoryCompressionTest {

    private static final int SUMMARY_CHAR_LIMIT = 4000;

    @Test
    void shouldLimitStoredSummaryAndKeepNewestSummaryAfterCompression() throws Exception {
        SummaryModel summaryModel = summaryModelReturning("latest-summary-" + "b".repeat(800));
        JChatMind agent = newAgent(summaryModel.chatClient());
        setConversationSummary(agent, "previous-summary-" + "a".repeat(3600));

        compressMemory(agent);

        String summary = conversationSummary(agent);
        assertThat(summary)
                .hasSizeLessThanOrEqualTo(SUMMARY_CHAR_LIMIT)
                .endsWith("latest-summary-" + "b".repeat(800));
    }

    @Test
    void shouldOnlySendBoundedPreviousSummaryToSummaryModel() throws Exception {
        SummaryModel summaryModel = summaryModelReturning("latest-summary");
        JChatMind agent = newAgent(summaryModel.chatClient());
        setConversationSummary(agent, "discarded-prefix-" + "a".repeat(5000));

        compressMemory(agent);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(summaryModel.requestSpec()).user(prompt.capture());
        assertThat(prompt.getValue()).doesNotContain("discarded-prefix-");
    }

    @Test
    void shouldOnlySendBoundedPreviousSummaryToDecisionPrompt() throws Exception {
        JChatMind agent = newAgent(summaryModelReturning("latest-summary").chatClient());
        setConversationSummary(agent, "discarded-prefix-" + "a".repeat(5000));

        String prompt = buildThinkPrompt(agent);

        assertThat(prompt).doesNotContain("discarded-prefix-");
    }

    private SummaryModel summaryModelReturning(String response) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(response);
        return new SummaryModel(chatClient, requestSpec);
    }

    private JChatMind newAgent(ChatClient chatClient) {
        return new JChatMind(
                "user-1",
                "agent-1",
                "test agent",
                "",
                "",
                chatClient,
                20,
                messagesForCompression(),
                List.of(),
                List.of(),
                "session-1",
                mock(SseService.class),
                mock(ChatMessageFacadeService.class),
                mock(ChatMessageConverter.class),
                mock(HarnessRunner.class)
        );
    }

    private List<Message> messagesForCompression() {
        List<Message> messages = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            messages.add(new UserMessage("message-" + index + "-" + "content".repeat(200)));
        }
        return messages;
    }

    private void compressMemory(JChatMind agent) throws Exception {
        Method method = JChatMind.class.getDeclaredMethod("compressMemoryIfNeeded");
        method.setAccessible(true);
        method.invoke(agent);
    }

    private String buildThinkPrompt(JChatMind agent) throws Exception {
        Method method = JChatMind.class.getDeclaredMethod("buildThinkPrompt");
        method.setAccessible(true);
        return (String) method.invoke(agent);
    }

    private void setConversationSummary(JChatMind agent, String summary) throws Exception {
        Field field = JChatMind.class.getDeclaredField("conversationSummary");
        field.setAccessible(true);
        field.set(agent, summary);
    }

    private String conversationSummary(JChatMind agent) throws Exception {
        Field field = JChatMind.class.getDeclaredField("conversationSummary");
        field.setAccessible(true);
        return (String) field.get(agent);
    }

    private record SummaryModel(ChatClient chatClient, ChatClient.ChatClientRequestSpec requestSpec) {
    }
}
