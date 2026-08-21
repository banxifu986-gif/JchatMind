package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.config.RabbitMQConfig;
import lombok.AllArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class RabbitIngestionTaskPublisher implements IngestionTaskPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(String taskId) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.INGESTION_EXCHANGE,
                RabbitMQConfig.INGESTION_ROUTING_KEY,
                taskId
        );
    }
}
