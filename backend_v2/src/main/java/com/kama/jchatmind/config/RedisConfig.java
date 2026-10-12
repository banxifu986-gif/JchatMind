package com.kama.jchatmind.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.service.impl.RedisChatSseEventStore;
import com.kama.jchatmind.service.impl.RedisChatSseEventSubscriber;
import com.kama.jchatmind.service.impl.SseServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    @Profile("!test")
    public RedisMessageListenerContainer chatSseEventListenerContainer(
            RedisConnectionFactory factory,
            ObjectMapper objectMapper,
            SseServiceImpl sseService
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(
                new RedisChatSseEventSubscriber(objectMapper, sseService),
                new PatternTopic(RedisChatSseEventStore.EVENT_CHANNEL_PATTERN)
        );
        return container;
    }
}
