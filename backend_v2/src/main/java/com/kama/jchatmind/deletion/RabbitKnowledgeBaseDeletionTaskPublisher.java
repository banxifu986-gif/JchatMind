package com.kama.jchatmind.deletion;

import com.kama.jchatmind.config.RabbitMQConfig;
import lombok.AllArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class RabbitKnowledgeBaseDeletionTaskPublisher implements KnowledgeBaseDeletionTaskPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(String taskId) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.KNOWLEDGE_BASE_DELETION_EXCHANGE,
                RabbitMQConfig.KNOWLEDGE_BASE_DELETION_ROUTING_KEY,
                taskId
        );
    }
}
