package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.IngestionTaskMapper;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import com.kama.jchatmind.service.impl.QueryRewriteServiceImpl;
import com.kama.jchatmind.service.impl.RagServiceImpl;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.function.client.WebClient;

@TestConfiguration
@EnableRabbit
@Import({G1RuntimePostgresTestConfig.class, RabbitMQConfig.class, IngestionTaskProgressServiceImpl.class})
public class G1RuntimeRabbitRecoveryTestConfig {

    @Bean
    public ConnectionFactory rabbitConnectionFactory() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
        connectionFactory.setHost(requiredProperty("g1.rabbit.host"));
        connectionFactory.setPort(Integer.parseInt(requiredProperty("g1.rabbit.port")));
        connectionFactory.setUsername(requiredProperty("g1.rabbit.username"));
        connectionFactory.setPassword(requiredProperty("g1.rabbit.password"));
        connectionFactory.setVirtualHost(requiredProperty("g1.rabbit.vhost"));
        return connectionFactory;
    }

    @Bean
    public AmqpAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean(name = "rabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public MarkdownParserService markdownParserService() {
        return new MarkdownParserServiceImpl();
    }

    @Bean
    public RagService ragService(ChunkBgeM3Mapper chunkBgeM3Mapper) {
        return new RagServiceImpl(
                WebClient.builder(),
                chunkBgeM3Mapper,
                new QueryRewriteServiceImpl(chunkBgeM3Mapper),
                "http://127.0.0.1:1",
                "g1-disabled-embedding",
                false,
                true,
                true,
                0
        );
    }

    @Bean
    public DefaultIngestionTaskProcessor defaultIngestionTaskProcessor(
            DocumentMapper documentMapper,
            com.kama.jchatmind.service.DocumentStorageService documentStorageService,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            MarkdownParserService markdownParserService,
            RagService ragService,
            ChunkBgeM3Mapper chunkBgeM3Mapper
    ) {
        return new DefaultIngestionTaskProcessor(
                documentMapper,
                documentStorageService,
                objectMapper,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper
        );
    }

    @Bean
    public IngestionTaskConsumer ingestionTaskConsumer(
            com.kama.jchatmind.service.impl.IngestionTaskServiceImpl ingestionTaskService,
            DefaultIngestionTaskProcessor processor,
            org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate,
            IngestionTaskProgressService progressService
    ) {
        return new IngestionTaskConsumer(ingestionTaskService, processor, rabbitTemplate, progressService);
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少测试属性: " + name);
        }
        return value;
    }
}
