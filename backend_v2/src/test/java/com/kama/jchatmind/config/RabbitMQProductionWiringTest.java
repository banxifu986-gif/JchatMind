package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQProductionWiringTest {

    @Test
    void shouldExposeDedicatedDeletionListenerFactoryWithRabbitAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RabbitAutoConfiguration.class,
                        rabbitAnnotationDrivenConfiguration()
                ))
                .withUserConfiguration(RabbitMQConfig.class, RabbitListenerConfiguration.class)
                .withPropertyValues(
                        "spring.rabbitmq.listener.simple.auto-startup=false",
                        "spring.rabbitmq.host=127.0.0.1",
                        "spring.rabbitmq.port=5672"
                )
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasBean("knowledgeBaseDeletionRabbitListenerContainerFactory"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRabbit
    static class RabbitListenerConfiguration {

        @org.springframework.context.annotation.Bean
        SimpleRabbitListenerContainerFactoryConfigurer rabbitListenerContainerFactoryConfigurer() {
            return new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties());
        }
    }

    private static Class<?> rabbitAnnotationDrivenConfiguration() {
        try {
            return Class.forName("org.springframework.boot.autoconfigure.amqp.RabbitAnnotationDrivenConfiguration");
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("Rabbit annotation-driven auto configuration is missing", exception);
        }
    }
}
