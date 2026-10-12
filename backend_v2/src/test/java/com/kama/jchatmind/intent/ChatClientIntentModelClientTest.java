package com.kama.jchatmind.intent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatClientIntentModelClientTest {

    @Test
    void shouldRequestOnlyVersionedIntentJsonFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.9,\"margin\":0.5}");

        ChatClientIntentModelClient client = new ChatClientIntentModelClient(chatClient);

        String response = client.classify("请查询项目文档");

        assertThat(response).contains("KNOWLEDGE_QA");
        verify(requestSpec).system(org.mockito.ArgumentMatchers.<String>argThat(prompt ->
                prompt.contains("businessIntent")
                        && prompt.contains("confidence")
                        && prompt.contains("margin")
                        && prompt.contains("不要输出授权")
        ));
        verify(requestSpec).user("请查询项目文档");
    }
}
