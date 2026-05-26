package com.kama.jchatmind.startup;

import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.service.impl.QueryRewriteServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CircularDependencyStartupTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldLoadContextWhenMcpServerProviderAndQueryRewriteLlmWiringCoexist() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ChatClientRegistry.class);
            assertThat(context).hasSingleBean(QueryRewriteServiceImpl.class);
        });
    }

    @Configuration
    static class TestConfig {

        @Bean("deepseek-chat")
        ChatClient deepseekChatClient() {
            return org.mockito.Mockito.mock(ChatClient.class);
        }

        @Bean
        ChatClientRegistry chatClientRegistry(@Qualifier("deepseek-chat") ChatClient chatClient) {
            return new ChatClientRegistry(Map.of("deepseek-chat", chatClient));
        }

        @Bean
        QueryRewriteServiceImpl queryRewriteService() {
            return new QueryRewriteServiceImpl(
                    null,
                    new StubChatClientProvider(),
                    true,
                    "deepseek-chat"
            );
        }

        @Bean("mcpToolCallbackProvider")
        ToolCallbackProvider mcpToolCallbackProvider(QueryRewriteServiceImpl queryRewriteService) {
            return () -> new ToolCallback[]{
                    new StubToolCallback("mcpKnowledgeQuery")
            };
        }
    }

    private static final class StubChatClientProvider implements ObjectProvider<ChatClientRegistry> {
        @Override
        public ChatClientRegistry getObject(Object... args) {
            return null;
        }

        @Override
        public ChatClientRegistry getIfAvailable() {
            return null;
        }
    }

    private static final class StubToolCallback implements ToolCallback {
        private final ToolDefinition toolDefinition;

        private StubToolCallback(String name) {
            this.toolDefinition = DefaultToolDefinition.builder()
                    .name(name)
                    .description(name)
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public String call(String toolInput) {
            return "";
        }
    }
}
