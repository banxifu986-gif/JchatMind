package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinSkillTaskRabbitTopologyTest {

    @Test
    void declaresDedicatedMainRetryAndDeadLetterTopology() throws Exception {
        RabbitMQConfig config = new RabbitMQConfig();
        assertThat(((DirectExchange) invoke(config, "skillTaskExchange")).getName())
                .isEqualTo(RabbitMQConfig.BUILTIN_SKILL_TASK_EXCHANGE);
        assertThat(((DirectExchange) invoke(config, "skillTaskRetryExchange")).getName())
                .isEqualTo(RabbitMQConfig.BUILTIN_SKILL_TASK_RETRY_EXCHANGE);
        assertThat(((DirectExchange) invoke(config, "skillTaskDeadLetterExchange")).getName())
                .isEqualTo(RabbitMQConfig.BUILTIN_SKILL_TASK_DLX);

        Queue mainQueue = (Queue) invoke(config, "skillTaskQueue");
        assertThat(mainQueue.getName()).isEqualTo(RabbitMQConfig.BUILTIN_SKILL_TASK_QUEUE);
        assertThat(mainQueue.isDurable()).isTrue();
        assertThat(mainQueue.getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitMQConfig.BUILTIN_SKILL_TASK_DLX)
                .containsEntry("x-dead-letter-routing-key", RabbitMQConfig.BUILTIN_SKILL_TASK_DLQ_ROUTING_KEY);

        Queue retryQueue = (Queue) invoke(config, "skillTaskRetryQueue");
        assertThat(retryQueue.getName()).isEqualTo(RabbitMQConfig.BUILTIN_SKILL_TASK_RETRY_QUEUE);
        assertThat(retryQueue.getArguments())
                .containsEntry("x-message-ttl", RabbitMQConfig.BUILTIN_SKILL_TASK_RETRY_TTL_MILLIS)
                .containsEntry("x-dead-letter-exchange", RabbitMQConfig.BUILTIN_SKILL_TASK_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", RabbitMQConfig.BUILTIN_SKILL_TASK_ROUTING_KEY);
        assertThat(((Queue) invoke(config, "skillTaskDeadLetterQueue")).getName())
                .isEqualTo(RabbitMQConfig.BUILTIN_SKILL_TASK_DLQ);

        assertThat(((Binding) invoke(config, "skillTaskBinding")).getRoutingKey())
                .isEqualTo(RabbitMQConfig.BUILTIN_SKILL_TASK_ROUTING_KEY);
        assertThat(((Binding) invoke(config, "skillTaskRetryBinding")).getRoutingKey())
                .isEqualTo(RabbitMQConfig.BUILTIN_SKILL_TASK_RETRY_ROUTING_KEY);
        assertThat(((Binding) invoke(config, "skillTaskDeadLetterBinding")).getRoutingKey())
                .isEqualTo(RabbitMQConfig.BUILTIN_SKILL_TASK_DLQ_ROUTING_KEY);
    }

    private Method beanMethod(String name) {
        return Arrays.stream(RabbitMQConfig.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private Object invoke(RabbitMQConfig config, String name) throws Exception {
        Method method = beanMethod(name);
        assertThat(method).isNotNull();
        return method.invoke(config);
    }
}
