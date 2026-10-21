package com.kama.jchatmind.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebhookRuntimeConfigurationTest {

    @Test
    void registersVerifierWithTheRedisEventIdRegistry() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    RedisWebhookEventIdRegistry.class,
                    () -> new RedisWebhookEventIdRegistry(mock(StringRedisTemplate.class))
            );
            context.register(WebhookRuntimeConfiguration.class);
            context.refresh();

            assertThat(context.getBeansOfType(InboundWebhookVerifier.class)).hasSize(1);
            InboundWebhookVerifier verifier = context.getBeansOfType(InboundWebhookVerifier.class).values().iterator().next();

            assertThat(ReflectionTestUtils.getField(verifier, "eventIdRegistry"))
                    .isSameAs(context.getBean(RedisWebhookEventIdRegistry.class));
        }
    }
}
