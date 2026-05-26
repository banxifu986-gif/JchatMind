package com.kama.jchatmind.agent;

import com.kama.jchatmind.config.ToolCallbackProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class JChatMindFactoryExternalToolProviderTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ToolCallbackProviderConfig.class, TestConfig.class);

    @Test
    void shouldExcludeMcpServerToolProviderFromExternalToolProviderList() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("externalToolCallbackProvider");
            ToolCallbackProvider provider = context.getBean("externalToolCallbackProvider", ToolCallbackProvider.class);
            assertThat(provider.getToolCallbacks())
                    .extracting(callback -> callback.getToolDefinition().name())
                    .containsExactly("externalKnowledge");
        });
    }

    @Configuration
    static class TestConfig {

        @Bean("mcpToolCallbackProvider")
        @org.springframework.beans.factory.annotation.Qualifier("mcpServerToolCallbackProvider")
        ToolCallbackProvider mcpToolCallbackProvider() {
            return () -> new ToolCallback[]{
                    new StubToolCallback("mcpKnowledgeQuery")
            };
        }

        @Bean("clientToolCallbackProvider")
        ToolCallbackProvider clientToolCallbackProvider() {
            return () -> new ToolCallback[]{
                    new StubToolCallback("externalKnowledge")
            };
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
