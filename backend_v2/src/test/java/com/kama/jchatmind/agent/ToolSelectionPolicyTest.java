package com.kama.jchatmind.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSelectionPolicyTest {

    private final ToolSelectionPolicy policy = new ToolSelectionPolicy();
    private final List<ToolCallback> callbacks = List.of(
            callback("KnowledgeTool"),
            callback("sendEmail"),
            callback("databaseQuery"),
            callback("weather"),
            callback("getDate"),
            callback("getCity"),
            callback("readFile")
    );

    @Test
    void shouldExposeNoToolsForDirectConversation() {
        assertThat(policy.select(callbacks, "你好")).isEmpty();
    }

    @Test
    void shouldExposeOnlyKnowledgeToolForKnowledgeQuestion() {
        assertThat(policy.select(callbacks, "根据项目文档，如何发布服务？"))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("KnowledgeTool");
    }

    @Test
    void shouldExposeKnowledgeToolForExplicitSearchRequest() {
        assertThat(policy.select(callbacks, "请查询资料并给出依据"))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("KnowledgeTool");
    }

    @Test
    void shouldExposeOnlyExplicitActionTool() {
        assertThat(policy.select(callbacks, "请发邮件给 ban@example.com"))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("sendEmail");
    }

    @Test
    void shouldNotExposeEmailActionForEmailPolicyQuestion() {
        assertThat(policy.select(callbacks, "项目文档中的邮件发送规范是什么？"))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("KnowledgeTool");
    }

    @Test
    void shouldNotExposeFileMutationToolsForFileDocumentationQuestion() {
        assertThat(policy.select(callbacks, "请说明项目文件上传规范"))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("KnowledgeTool");
    }

    @Test
    void shouldNotExposeEmailActionForHowToQuestion() {
        assertThat(policy.select(callbacks, "如何发邮件"))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("KnowledgeTool");
    }

    @Test
    void shouldNotExposeDatabaseActionForHowToQuestion() {
        assertThat(policy.select(callbacks, "如何执行 SQL 查询"))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("KnowledgeTool");
    }

    @Test
    void shouldNotExposeFileActionForHowToQuestion() {
        assertThat(policy.select(callbacks, "如何读取文件"))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("KnowledgeTool");
    }

    @Test
    void shouldExposeNoToolWhenIntentIsNotRecognizable() {
        assertThat(policy.select(callbacks, "帮我写一首短诗"))
                .isEmpty();
    }

    private ToolCallback callback(String name) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(name)
                .description(name)
                .inputSchema("{}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }
}
