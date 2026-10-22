package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.mapper.AgentExecutionTaskMapper;
import com.kama.jchatmind.model.dto.AgentExecutionTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutionTaskEnqueueListenerTest {

    @Test
    void shouldPersistTurnIdentityWithoutDuplicatingUserPrompt() {
        AtomicReference<AgentExecutionTask> insertedTask = new AtomicReference<>();
        AgentExecutionTaskMapper mapper = (AgentExecutionTaskMapper) Proxy.newProxyInstance(
                AgentExecutionTaskMapper.class.getClassLoader(),
                new Class<?>[]{AgentExecutionTaskMapper.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("insert")) {
                        insertedTask.set((AgentExecutionTask) arguments[0]);
                        return 1;
                    }
                    return null;
                }
        );
        AgentExecutionTaskEnqueueListener listener = new AgentExecutionTaskEnqueueListener(mapper);

        listener.enqueue(new ChatEvent("7", "agent-1", "session-1", "message-1", 4L, "private prompt"));

        assertThat(insertedTask.get())
                .extracting(
                        AgentExecutionTask::getSessionId,
                        AgentExecutionTask::getUserId,
                        AgentExecutionTask::getAgentId,
                        AgentExecutionTask::getUserMessageId,
                        AgentExecutionTask::getSequenceNo,
                        AgentExecutionTask::getStatus
                )
                .containsExactly("session-1", "7", "agent-1", "message-1", 4L, "PENDING");
        assertThat(insertedTask.get().getId()).isNotBlank();
    }

    @Test
    void shouldRejectEventsWithoutPersistedTurnSequence() {
        AgentExecutionTaskMapper mapper = (AgentExecutionTaskMapper) Proxy.newProxyInstance(
                AgentExecutionTaskMapper.class.getClassLoader(),
                new Class<?>[]{AgentExecutionTaskMapper.class},
                (proxy, method, arguments) -> 1
        );

        assertThatThrownBy(() -> new AgentExecutionTaskEnqueueListener(mapper)
                .enqueue(new ChatEvent("7", "agent-1", "session-1", "message-1", null, "prompt")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Agent turn sequence cannot be null");
    }
}
