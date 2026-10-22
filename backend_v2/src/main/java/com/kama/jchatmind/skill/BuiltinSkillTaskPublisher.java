package com.kama.jchatmind.skill;

import com.kama.jchatmind.config.RabbitMQConfig;
import lombok.AllArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class BuiltinSkillTaskPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publish(String taskId) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.BUILTIN_SKILL_TASK_EXCHANGE,
                RabbitMQConfig.BUILTIN_SKILL_TASK_ROUTING_KEY,
                taskId
        );
    }
}
