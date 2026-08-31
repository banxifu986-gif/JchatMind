package com.kama.jchatmind.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    public static final String EMAIL_EXCHANGE = "email.exchange";
    public static final String EMAIL_QUEUE = "email.queue";
    public static final String EMAIL_ROUTING_KEY = "email.send";

    public static final String EMAIL_RETRY_EXCHANGE = "email.retry.exchange";
    public static final String EMAIL_RETRY_QUEUE = "email.retry.queue";
    public static final String EMAIL_RETRY_ROUTING_KEY = "email.retry";

    public static final String EMAIL_DLX = "email.dlx";
    public static final String EMAIL_DLQ = "email.dlq";
    public static final String EMAIL_DLQ_ROUTING_KEY = "email.dlq";

    public static final int EMAIL_MAX_RETRY_COUNT = 3;
    public static final int EMAIL_RETRY_TTL_MILLIS = 30_000;

    public static final String INGESTION_EXCHANGE = "ingestion.exchange";
    public static final String INGESTION_QUEUE = "ingestion.queue";
    public static final String INGESTION_ROUTING_KEY = "ingestion.submit";

    public static final String INGESTION_RETRY_EXCHANGE = "ingestion.retry.exchange";
    public static final String INGESTION_RETRY_QUEUE = "ingestion.retry.queue";
    public static final String INGESTION_RETRY_ROUTING_KEY = "ingestion.retry";

    public static final String INGESTION_DLX = "ingestion.dlx";
    public static final String INGESTION_DLQ = "ingestion.dlq";
    public static final String INGESTION_DLQ_ROUTING_KEY = "ingestion.dlq";

    public static final int INGESTION_MAX_RETRY_COUNT = 3;
    public static final int INGESTION_RETRY_TTL_MILLIS = 30_000;
    public static final int INGESTION_CONCURRENT_CONSUMERS = 2;
    public static final int INGESTION_PREFETCH_COUNT = 1;

    public static final String KNOWLEDGE_BASE_DELETION_EXCHANGE = "knowledge-base-deletion.exchange";
    public static final String KNOWLEDGE_BASE_DELETION_QUEUE = "knowledge-base-deletion.queue";
    public static final String KNOWLEDGE_BASE_DELETION_ROUTING_KEY = "knowledge-base-deletion.submit";

    public static final String KNOWLEDGE_BASE_DELETION_RETRY_EXCHANGE = "knowledge-base-deletion.retry.exchange";
    public static final String KNOWLEDGE_BASE_DELETION_RETRY_QUEUE = "knowledge-base-deletion.retry.queue";
    public static final String KNOWLEDGE_BASE_DELETION_RETRY_ROUTING_KEY = "knowledge-base-deletion.retry";

    public static final String KNOWLEDGE_BASE_DELETION_DLX = "knowledge-base-deletion.dlx";
    public static final String KNOWLEDGE_BASE_DELETION_DLQ = "knowledge-base-deletion.dlq";
    public static final String KNOWLEDGE_BASE_DELETION_DLQ_ROUTING_KEY = "knowledge-base-deletion.dlq";

    public static final int KNOWLEDGE_BASE_DELETION_RETRY_TTL_MILLIS = 30_000;
    public static final int KNOWLEDGE_BASE_DELETION_CONCURRENT_CONSUMERS = 1;
    public static final int KNOWLEDGE_BASE_DELETION_PREFETCH_COUNT = 1;

    @Bean
    public DirectExchange emailExchange() {
        return new DirectExchange(EMAIL_EXCHANGE);
    }

    @Bean
    public DirectExchange emailRetryExchange() {
        return new DirectExchange(EMAIL_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange emailDlx() {
        return new DirectExchange(EMAIL_DLX);
    }

    @Bean
    public Queue emailQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", EMAIL_DLX);
        args.put("x-dead-letter-routing-key", EMAIL_DLQ_ROUTING_KEY);
        return QueueBuilder.durable(EMAIL_QUEUE)
                .withArguments(args)
                .build();
    }

    @Bean
    public Queue emailRetryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", EMAIL_EXCHANGE);
        args.put("x-dead-letter-routing-key", EMAIL_ROUTING_KEY);
        args.put("x-message-ttl", EMAIL_RETRY_TTL_MILLIS);
        return QueueBuilder.durable(EMAIL_RETRY_QUEUE)
                .withArguments(args)
                .build();
    }

    @Bean
    public Queue emailDlq() {
        return QueueBuilder.durable(EMAIL_DLQ).build();
    }

    @Bean
    public Binding emailBinding() {
        return BindingBuilder.bind(emailQueue())
                .to(emailExchange())
                .with(EMAIL_ROUTING_KEY);
    }

    @Bean
    public Binding emailRetryBinding() {
        return BindingBuilder.bind(emailRetryQueue())
                .to(emailRetryExchange())
                .with(EMAIL_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding emailDlqBinding() {
        return BindingBuilder.bind(emailDlq())
                .to(emailDlx())
                .with(EMAIL_DLQ_ROUTING_KEY);
    }

    @Bean
    public DirectExchange ingestionExchange() {
        return new DirectExchange(INGESTION_EXCHANGE);
    }

    @Bean
    public DirectExchange ingestionRetryExchange() {
        return new DirectExchange(INGESTION_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange ingestionDlx() {
        return new DirectExchange(INGESTION_DLX);
    }

    @Bean
    public Queue ingestionQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", INGESTION_DLX);
        args.put("x-dead-letter-routing-key", INGESTION_DLQ_ROUTING_KEY);
        return QueueBuilder.durable(INGESTION_QUEUE)
                .withArguments(args)
                .build();
    }

    @Bean
    public Queue ingestionRetryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", INGESTION_EXCHANGE);
        args.put("x-dead-letter-routing-key", INGESTION_ROUTING_KEY);
        args.put("x-message-ttl", INGESTION_RETRY_TTL_MILLIS);
        return QueueBuilder.durable(INGESTION_RETRY_QUEUE)
                .withArguments(args)
                .build();
    }

    @Bean
    public Queue ingestionDlq() {
        return QueueBuilder.durable(INGESTION_DLQ).build();
    }

    @Bean
    public Binding ingestionBinding() {
        return BindingBuilder.bind(ingestionQueue())
                .to(ingestionExchange())
                .with(INGESTION_ROUTING_KEY);
    }

    @Bean
    public Binding ingestionRetryBinding() {
        return BindingBuilder.bind(ingestionRetryQueue())
                .to(ingestionRetryExchange())
                .with(INGESTION_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding ingestionDlqBinding() {
        return BindingBuilder.bind(ingestionDlq())
                .to(ingestionDlx())
                .with(INGESTION_DLQ_ROUTING_KEY);
    }

    @Bean
    public DirectExchange knowledgeBaseDeletionExchange() {
        return new DirectExchange(KNOWLEDGE_BASE_DELETION_EXCHANGE);
    }

    @Bean
    public DirectExchange knowledgeBaseDeletionRetryExchange() {
        return new DirectExchange(KNOWLEDGE_BASE_DELETION_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange knowledgeBaseDeletionDlx() {
        return new DirectExchange(KNOWLEDGE_BASE_DELETION_DLX);
    }

    @Bean
    public Queue knowledgeBaseDeletionQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", KNOWLEDGE_BASE_DELETION_DLX);
        args.put("x-dead-letter-routing-key", KNOWLEDGE_BASE_DELETION_DLQ_ROUTING_KEY);
        return QueueBuilder.durable(KNOWLEDGE_BASE_DELETION_QUEUE)
                .withArguments(args)
                .build();
    }

    @Bean
    public Queue knowledgeBaseDeletionRetryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", KNOWLEDGE_BASE_DELETION_EXCHANGE);
        args.put("x-dead-letter-routing-key", KNOWLEDGE_BASE_DELETION_ROUTING_KEY);
        args.put("x-message-ttl", KNOWLEDGE_BASE_DELETION_RETRY_TTL_MILLIS);
        return QueueBuilder.durable(KNOWLEDGE_BASE_DELETION_RETRY_QUEUE)
                .withArguments(args)
                .build();
    }

    @Bean
    public Queue knowledgeBaseDeletionDlq() {
        return QueueBuilder.durable(KNOWLEDGE_BASE_DELETION_DLQ).build();
    }

    @Bean
    public Binding knowledgeBaseDeletionBinding() {
        return BindingBuilder.bind(knowledgeBaseDeletionQueue())
                .to(knowledgeBaseDeletionExchange())
                .with(KNOWLEDGE_BASE_DELETION_ROUTING_KEY);
    }

    @Bean
    public Binding knowledgeBaseDeletionRetryBinding() {
        return BindingBuilder.bind(knowledgeBaseDeletionRetryQueue())
                .to(knowledgeBaseDeletionRetryExchange())
                .with(KNOWLEDGE_BASE_DELETION_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding knowledgeBaseDeletionDlqBinding() {
        return BindingBuilder.bind(knowledgeBaseDeletionDlq())
                .to(knowledgeBaseDeletionDlx())
                .with(KNOWLEDGE_BASE_DELETION_DLQ_ROUTING_KEY);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory ingestionRabbitListenerContainerFactory(
            @Nullable SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        if (configurer != null) {
            configurer.configure(factory, connectionFactory);
        }
        factory.setConcurrentConsumers(INGESTION_CONCURRENT_CONSUMERS);
        factory.setMaxConcurrentConsumers(INGESTION_CONCURRENT_CONSUMERS);
        factory.setPrefetchCount(INGESTION_PREFETCH_COUNT);
        return factory;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory knowledgeBaseDeletionRabbitListenerContainerFactory(
            @Nullable SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        if (configurer != null) {
            configurer.configure(factory, connectionFactory);
        }
        factory.setConcurrentConsumers(KNOWLEDGE_BASE_DELETION_CONCURRENT_CONSUMERS);
        factory.setMaxConcurrentConsumers(KNOWLEDGE_BASE_DELETION_CONCURRENT_CONSUMERS);
        factory.setPrefetchCount(KNOWLEDGE_BASE_DELETION_PREFETCH_COUNT);
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter(mapper));
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack && correlationData != null) {
                // Publisher confirm failed — handled by DLX retry
            }
        });
        return template;
    }
}
