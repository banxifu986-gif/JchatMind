package com.kama.jchatmind.deletion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.ingestion.G1RuntimePostgresTestConfig;
import com.kama.jchatmind.mapper.KnowledgeBaseDeletionTaskMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.impl.KnowledgeBaseDeletionTaskServiceImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@TestConfiguration
@EnableRabbit
@Import(G1RuntimePostgresTestConfig.class)
public class G1KnowledgeBaseDeletionRuntimeL2TestConfig {

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
    public AmqpAdmin rabbitAdmin(ConnectionFactory rabbitConnectionFactory) {
        return new RabbitAdmin(rabbitConnectionFactory);
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(
            ConnectionFactory rabbitConnectionFactory,
            MessageConverter rabbitMessageConverter
    ) {
        RabbitTemplate template = new RabbitTemplate(rabbitConnectionFactory);
        template.setMessageConverter(rabbitMessageConverter);
        return template;
    }

    @Bean
    public DirectExchange knowledgeBaseDeletionExchange() {
        return new DirectExchange(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_EXCHANGE);
    }

    @Bean
    public DirectExchange knowledgeBaseDeletionRetryExchange() {
        return new DirectExchange(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange knowledgeBaseDeletionDlx() {
        return new DirectExchange(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_DLX);
    }

    @Bean
    public Queue knowledgeBaseDeletionQueue() {
        return QueueBuilder.durable(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_QUEUE)
                .deadLetterExchange(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_DLX)
                .deadLetterRoutingKey(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue knowledgeBaseDeletionRetryQueue() {
        return QueueBuilder.durable(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_RETRY_QUEUE)
                .deadLetterExchange(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_EXCHANGE)
                .deadLetterRoutingKey(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_ROUTING_KEY)
                .ttl(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_RETRY_TTL_MILLIS)
                .build();
    }

    @Bean
    public Queue knowledgeBaseDeletionDlq() {
        return QueueBuilder.durable(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_DLQ).build();
    }

    @Bean
    public Binding knowledgeBaseDeletionBinding() {
        return BindingBuilder.bind(knowledgeBaseDeletionQueue())
                .to(knowledgeBaseDeletionExchange())
                .with(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_ROUTING_KEY);
    }

    @Bean
    public Binding knowledgeBaseDeletionRetryBinding() {
        return BindingBuilder.bind(knowledgeBaseDeletionRetryQueue())
                .to(knowledgeBaseDeletionRetryExchange())
                .with(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding knowledgeBaseDeletionDlqBinding() {
        return BindingBuilder.bind(knowledgeBaseDeletionDlq())
                .to(knowledgeBaseDeletionDlx())
                .with(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_DLQ_ROUTING_KEY);
    }

    @Bean("knowledgeBaseDeletionRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory knowledgeBaseDeletionRabbitListenerContainerFactory(
            ConnectionFactory rabbitConnectionFactory,
            MessageConverter rabbitMessageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(rabbitConnectionFactory);
        factory.setMessageConverter(rabbitMessageConverter);
        factory.setConcurrentConsumers(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_CONCURRENT_CONSUMERS);
        factory.setMaxConcurrentConsumers(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_CONCURRENT_CONSUMERS);
        factory.setPrefetchCount(RabbitMQConfig.KNOWLEDGE_BASE_DELETION_PREFETCH_COUNT);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean("knowledgeBaseDeletionSqlSessionFactory")
    public SqlSessionFactory knowledgeBaseDeletionSqlSessionFactory(
            javax.sql.DataSource dataSource
    ) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResource("classpath:mapper/KnowledgeBaseDeletionTaskMapper.xml"));
        return factory.getObject();
    }

    @Bean
    public MapperFactoryBean<KnowledgeBaseDeletionTaskMapper> knowledgeBaseDeletionTaskMapper(
            @Qualifier("knowledgeBaseDeletionSqlSessionFactory") SqlSessionFactory sqlSessionFactory
    ) {
        MapperFactoryBean<KnowledgeBaseDeletionTaskMapper> factory = new MapperFactoryBean<>(
                KnowledgeBaseDeletionTaskMapper.class
        );
        factory.setSqlSessionFactory(sqlSessionFactory);
        return factory;
    }

    @Bean
    public KnowledgeBaseDeletionTaskPublisher knowledgeBaseDeletionTaskPublisher(
            RabbitTemplate rabbitTemplate
    ) {
        return new RabbitKnowledgeBaseDeletionTaskPublisher(rabbitTemplate);
    }

    @Bean
    public KnowledgeBaseDeletionTaskServiceImpl knowledgeBaseDeletionTaskService(
            KnowledgeBaseDeletionTaskMapper deletionTaskMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            RequestScopeData requestScopeData,
            KnowledgeBaseDeletionTaskPublisher deletionTaskPublisher,
            ObjectMapper objectMapper
    ) {
        return new KnowledgeBaseDeletionTaskServiceImpl(
                deletionTaskMapper,
                knowledgeBaseMapper,
                knowledgeBaseAccessService,
                requestScopeData,
                deletionTaskPublisher,
                objectMapper
        );
    }

    @Bean
    public KnowledgeBaseDeletionTaskConsumer knowledgeBaseDeletionTaskConsumer(
            KnowledgeBaseDeletionTaskServiceImpl deletionTaskService,
            DocumentStorageService documentStorageService,
            RabbitTemplate rabbitTemplate
    ) {
        return new KnowledgeBaseDeletionTaskConsumer(
                deletionTaskService,
                documentStorageService,
                rabbitTemplate
        );
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少测试属性: " + name);
        }
        return value;
    }
}
