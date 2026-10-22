package com.kama.jchatmind.event.listener;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutionTurnContextTest {

    @Test
    void shouldSetAndRestoreTurnSequenceAroundAgentExecution() {
        AgentExecutionTurnContext.run(4L, () -> {
            assertThat(AgentExecutionTurnContext.currentSequence()).isEqualTo(4L);
            AgentExecutionTurnContext.run(5L, () ->
                    assertThat(AgentExecutionTurnContext.currentSequence()).isEqualTo(5L)
            );
            assertThat(AgentExecutionTurnContext.currentSequence()).isEqualTo(4L);
        });

        assertThat(AgentExecutionTurnContext.currentSequence()).isNull();
    }

    @Test
    void shouldClearTurnSequenceWhenAgentExecutionFails() {
        assertThatThrownBy(() -> AgentExecutionTurnContext.run(9L, () -> {
            assertThat(AgentExecutionTurnContext.currentSequence()).isEqualTo(9L);
            throw new IllegalStateException("expected");
        })).isInstanceOf(IllegalStateException.class).hasMessage("expected");

        assertThat(AgentExecutionTurnContext.currentSequence()).isNull();
    }
}
