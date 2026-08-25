package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.config.RabbitMQConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.beans.DirectFieldAccessor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IngestionWorkerConcurrencyConfigTest {

    @Test
    void shouldBindIngestionWorkerToDedicatedBoundedContainer() throws Exception {
        Optional<Method> factoryMethod = Arrays.stream(RabbitMQConfig.class.getMethods())
                .filter(method -> method.getName().equals("ingestionRabbitListenerContainerFactory"))
                .findFirst();

        assertThat(factoryMethod).isPresent();
        assertThat(factoryMethod.orElseThrow().getParameterTypes()).containsExactly(
                SimpleRabbitListenerContainerFactoryConfigurer.class,
                ConnectionFactory.class
        );

        SimpleRabbitListenerContainerFactory factory = (SimpleRabbitListenerContainerFactory) factoryMethod.orElseThrow()
                .invoke(
                        new RabbitMQConfig(),
                        new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties()),
                        mock(ConnectionFactory.class)
                );
        DirectFieldAccessor accessor = new DirectFieldAccessor(factory);
        assertThat(accessor.getPropertyValue("concurrentConsumers")).isEqualTo(2);
        assertThat(accessor.getPropertyValue("maxConcurrentConsumers")).isEqualTo(2);
        assertThat(accessor.getPropertyValue("prefetchCount")).isEqualTo(1);

        Method onMessage = IngestionTaskConsumer.class.getMethod("onMessage", String.class);
        assertThat(onMessage.getAnnotation(RabbitListener.class).containerFactory())
                .isEqualTo("ingestionRabbitListenerContainerFactory");
    }
}
