package com.kama.jchatmind.rag;

import com.kama.jchatmind.config.ChatClientRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class G2CurrentMultimodalAnswerEvaluationRuntimeConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ChatClientRegistry.class,
                    G2CurrentMultimodalCandidateImportL2Test.AnswerGenerationRuntimeConfig.class,
                    G2CurrentMultimodalCandidateImportL2Test.RefusalJudgeRuntimeConfig.class
            )
            .withBean(DeepSeekChatModel.class, () -> mock(DeepSeekChatModel.class))
            .withBean(ZhiPuAiChatModel.class, () -> mock(ZhiPuAiChatModel.class));

    @Test
    void registersGeneratorAndRefusalJudgeOnlyForTheirExplicitFlags() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatClientRegistry.class).getAllClients()).isEmpty();
        });

        contextRunner.withPropertyValues("g2.current.multimodal.answer-generation.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ChatClientRegistry registry = context.getBean(ChatClientRegistry.class);
                    assertThat(registry.get("deepseek-multimodal")).isNotNull();
                    assertThat(registry.get("glm-multimodal-refusal")).isNull();
                    assertThat(registry.getAllClients()).hasSize(1);
                });

        contextRunner.withPropertyValues("g2.current.multimodal.answer-refusal-judge.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatClientRegistry.class).getAllClients()).isEmpty();
                });

        contextRunner.withPropertyValues(
                        "g2.current.multimodal.answer-generation.enabled=true",
                        "g2.current.multimodal.answer-refusal-judge.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ChatClientRegistry registry = context.getBean(ChatClientRegistry.class);
                    assertThat(registry.get("deepseek-multimodal")).isNotNull();
                    assertThat(registry.get("glm-multimodal-refusal")).isNotNull();
                    assertThat(registry.get("deepseek-multimodal"))
                            .isNotSameAs(registry.get("glm-multimodal-refusal"));
                    assertThat(registry.getAllClients()).hasSize(2);
                });
    }

    @Test
    void registersGeneratorFromTheOptInDeepSeekAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DeepSeekChatAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class,
                        SpringAiRetryAutoConfiguration.class
                ))
                .withUserConfiguration(
                        ChatClientRegistry.class,
                        G2CurrentMultimodalCandidateImportL2Test.AnswerGenerationRuntimeConfig.class
                )
                .withPropertyValues(
                        "spring.ai.model.chat=deepseek",
                        "spring.ai.deepseek.api-key=test-dummy-key",
                        "g2.current.multimodal.answer-generation.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DeepSeekChatModel.class);
                    assertThat(context.getBean(ChatClientRegistry.class)
                            .get("deepseek-multimodal")).isNotNull();
                });
    }
}
