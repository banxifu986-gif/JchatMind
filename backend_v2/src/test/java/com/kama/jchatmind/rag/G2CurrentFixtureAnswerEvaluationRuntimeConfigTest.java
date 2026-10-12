package com.kama.jchatmind.rag;

import com.kama.jchatmind.config.ChatClientRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class G2CurrentFixtureAnswerEvaluationRuntimeConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ChatClientRegistry.class,
                    G2CurrentFixtureRuntimeImportL2Test.AnswerGenerationRuntimeConfig.class,
                    G2CurrentFixtureRuntimeImportL2Test.AnswerQualityJudgeRuntimeConfig.class
            )
            .withBean(DeepSeekChatModel.class, () -> mock(DeepSeekChatModel.class))
            .withBean(ZhiPuAiChatModel.class, () -> mock(ZhiPuAiChatModel.class));

    private final ApplicationContextRunner multimodalContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ChatClientRegistry.class,
                    G2CurrentMultimodalCandidateImportL2Test.AnswerGenerationRuntimeConfig.class,
                    G2CurrentMultimodalCandidateImportL2Test.RefusalJudgeRuntimeConfig.class
            )
            .withBean(DeepSeekChatModel.class, () -> mock(DeepSeekChatModel.class))
            .withBean(ZhiPuAiChatModel.class, () -> mock(ZhiPuAiChatModel.class));

    @Test
    void registersNoModelClientsByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("deepseek-chat");
            assertThat(context).doesNotHaveBean("glm-4.6");
            assertThat(context.getBean(ChatClientRegistry.class).getAllClients()).isEmpty();
        });
    }

    @Test
    void registersOnlyTheGeneratorWhenOnlyGenerationIsEnabled() {
        contextRunner.withPropertyValues("g2.current.fixture.answer-generation.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ChatClientRegistry registry = context.getBean(ChatClientRegistry.class);
                    assertThat(registry.get("deepseek-chat")).isNotNull();
                    assertThat(registry.get("glm-4.6")).isNull();
                    assertThat(registry.getAllClients()).hasSize(1);
                });
    }

    @Test
    void registersAnIndependentJudgeOnlyWhenBothFlagsAreEnabled() {
        contextRunner.withPropertyValues(
                        "g2.current.fixture.answer-generation.enabled=true",
                        "g2.current.fixture.answer-quality.judge.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ChatClientRegistry registry = context.getBean(ChatClientRegistry.class);
                    assertThat(registry.get("deepseek-chat")).isNotNull();
                    assertThat(registry.get("glm-4.6")).isNotNull();
                    assertThat(registry.get("deepseek-chat")).isNotSameAs(registry.get("glm-4.6"));
                    assertThat(registry.getAllClients()).hasSize(2);
                });
    }

    @Test
    void registersNoMultimodalGeneratorUnlessItsOwnFlagIsEnabled() {
        multimodalContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatClientRegistry.class).getAllClients()).isEmpty();
        });

        multimodalContextRunner.withPropertyValues("g2.current.multimodal.answer-generation.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ChatClientRegistry registry = context.getBean(ChatClientRegistry.class);
                    assertThat(registry.get("deepseek-multimodal")).isNotNull();
                    assertThat(registry.getAllClients()).hasSize(1);
                });

        multimodalContextRunner.withPropertyValues("g2.current.multimodal.answer-refusal-judge.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatClientRegistry.class).getAllClients()).isEmpty();
                });

        multimodalContextRunner.withPropertyValues(
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
}
