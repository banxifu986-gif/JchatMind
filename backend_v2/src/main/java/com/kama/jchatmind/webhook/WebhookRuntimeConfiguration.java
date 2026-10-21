package com.kama.jchatmind.webhook;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class WebhookRuntimeConfiguration {

    @Bean
    InboundWebhookVerifier inboundWebhookVerifier(WebhookEventIdRegistry eventIdRegistry) {
        return new InboundWebhookVerifier(Clock.systemUTC(), eventIdRegistry);
    }
}
