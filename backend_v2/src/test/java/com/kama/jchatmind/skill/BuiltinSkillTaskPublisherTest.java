package com.kama.jchatmind.skill;

import com.kama.jchatmind.config.RabbitMQConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BuiltinSkillTaskPublisherTest {

    @Test
    void publishesOnlyThePersistedTaskIdToTheDedicatedQueue() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        BuiltinSkillTaskPublisher publisher = new BuiltinSkillTaskPublisher(rabbitTemplate);

        publisher.publish("task-1");

        verify(rabbitTemplate).convertAndSend(
                RabbitMQConfig.BUILTIN_SKILL_TASK_EXCHANGE,
                RabbitMQConfig.BUILTIN_SKILL_TASK_ROUTING_KEY,
                "task-1"
        );
    }
}
