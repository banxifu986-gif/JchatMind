package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.DocumentAssetMapper;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import com.kama.jchatmind.service.impl.QueryRewriteServiceImpl;
import com.kama.jchatmind.service.impl.RagServiceImpl;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@TestConfiguration
@EnableRabbit
@Import({G1RuntimePostgresTestConfig.class, RabbitMQConfig.class, IngestionTaskProgressServiceImpl.class})
public class G1RuntimeEmbeddingRecoveryTestConfig {

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
    public EmbeddingRecoveryProbe embeddingRecoveryProbe() {
        return new EmbeddingRecoveryProbe();
    }

    @Bean
    public IngestionProcessingProbe ingestionProcessingProbe() {
        return new IngestionProcessingProbe();
    }

    @Bean
    public RagService ragService(
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            EmbeddingRecoveryProbe embeddingRecoveryProbe
    ) {
        RagService unavailable = new RagServiceImpl(
                WebClient.builder(),
                chunkBgeM3Mapper,
                new QueryRewriteServiceImpl(chunkBgeM3Mapper),
                "http://127.0.0.1:1",
                "g1-unavailable-embedding",
                false,
                true,
                true,
                0
        );
        RagService recovered = new RagServiceImpl(
                WebClient.builder(),
                chunkBgeM3Mapper,
                new QueryRewriteServiceImpl(chunkBgeM3Mapper),
                requiredProperty("g1.ollama.base-url"),
                requiredProperty("g1.ollama.model"),
                false,
                true,
                true,
                0
        );
        return new FailFirstEmbeddingRagService(unavailable, recovered, embeddingRecoveryProbe);
    }

    @Bean
    public DefaultIngestionTaskProcessor defaultIngestionTaskProcessor(
            DocumentMapper documentMapper,
            DocumentStorageService documentStorageService,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            MarkdownParserService markdownParserService,
            RagService ragService,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            DocumentAssetMapper documentAssetMapper
    ) {
        return new DefaultIngestionTaskProcessor(
                documentMapper,
                documentStorageService,
                objectMapper,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper,
                documentAssetMapper
        );
    }

    @Bean
    public IngestionTaskConsumer ingestionTaskConsumer(
            com.kama.jchatmind.service.impl.IngestionTaskServiceImpl ingestionTaskService,
            DefaultIngestionTaskProcessor processor,
            org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate,
            IngestionTaskProgressService progressService,
            IngestionProcessingProbe ingestionProcessingProbe
    ) {
        return new IngestionTaskConsumer(
                ingestionTaskService,
                task -> {
                    try {
                        processor.process(task);
                    } catch (RuntimeException e) {
                        ingestionProcessingProbe.recordFailure(e);
                        throw e;
                    }
                },
                rabbitTemplate,
                progressService
        );
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少测试属性: " + name);
        }
        return value;
    }

    private static class FailFirstEmbeddingRagService implements RagService {

        private final RagService unavailable;
        private final RagService recovered;
        private final EmbeddingRecoveryProbe probe;
        private final AtomicBoolean firstEmbedding = new AtomicBoolean(true);

        private FailFirstEmbeddingRagService(
                RagService unavailable,
                RagService recovered,
                EmbeddingRecoveryProbe probe
        ) {
            this.unavailable = unavailable;
            this.recovered = recovered;
            this.probe = probe;
        }

        @Override
        public float[] embed(String text) {
            boolean firstAttempt = firstEmbedding.compareAndSet(true, false);
            probe.recordAttempt(firstAttempt);
            try {
                if (firstAttempt) {
                    return unavailable.embed(text);
                }
                return recovered.embed(text);
            } catch (RuntimeException e) {
                probe.recordFailure(e);
                throw e;
            }
        }

        @Override
        public List<String> similaritySearch(List<String> kbIds, String title) {
            return recovered.similaritySearch(kbIds, title);
        }

        @Override
        public List<RagRetrievalResult> retrieve(List<String> kbIds, String query, int limit) {
            return recovered.retrieve(kbIds, query, limit);
        }

        @Override
        public List<RagRetrievalResult> retrieve(
                List<String> kbIds,
                String query,
                RagRetrievalContext context,
                int limit
        ) {
            return recovered.retrieve(kbIds, query, context, limit);
        }
    }

    static class EmbeddingRecoveryProbe {

        private final AtomicInteger attemptCount = new AtomicInteger();
        private volatile String lastEndpoint = "none";
        private volatile String lastFailure = "none";

        void recordAttempt(boolean unavailable) {
            attemptCount.incrementAndGet();
            lastEndpoint = unavailable ? "unavailable" : "recovered";
        }

        void recordFailure(RuntimeException failure) {
            lastFailure = failure.getClass().getSimpleName();
        }

        int attemptCount() {
            return attemptCount.get();
        }

        String lastEndpoint() {
            return lastEndpoint;
        }

        String lastFailure() {
            return lastFailure;
        }
    }

    static class IngestionProcessingProbe {

        private volatile String lastFailure = "none";

        void recordFailure(RuntimeException failure) {
            lastFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }

        String lastFailure() {
            return lastFailure;
        }
    }
}
