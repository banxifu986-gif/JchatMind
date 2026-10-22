package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.service.impl.ChatMessageFacadeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionQueueTransactionContractTest {

    @Test
    void shouldRequireTheQueueEventToJoinTheUserMessageTransaction() throws Exception {
        Transactional messageTransaction = ChatMessageFacadeServiceImpl.class
                .getMethod("createChatMessage", CreateChatMessageRequest.class)
                .getAnnotation(Transactional.class);
        Transactional enqueueTransaction = AgentExecutionTaskEnqueueListener.class
                .getMethod("enqueue", ChatEvent.class)
                .getAnnotation(Transactional.class);

        assertThat(messageTransaction).isNotNull();
        assertThat(enqueueTransaction).isNotNull();
        assertThat(enqueueTransaction.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
