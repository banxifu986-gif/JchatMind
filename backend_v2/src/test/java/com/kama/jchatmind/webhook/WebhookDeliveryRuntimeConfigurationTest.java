package com.kama.jchatmind.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.workflow.WorkflowExecutionAuditRecorder;
import com.kama.jchatmind.workflow.WorkflowExecutionAuditRetentionJob;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebhookDeliveryRuntimeConfigurationTest {

    @Test
    void shouldAssembleDeliveryAndAuditWorkersAsScheduledSpringBeans() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.register(TestConfiguration.class);
            context.refresh();

            assertThat(context.getBean(WebhookDeliveryDispatcher.class)).isNotNull();
            assertThat(context.getBean(WebhookApprovalOutboxService.class)).isNotNull();
            assertThat(context.getBean(WorkflowExecutionAuditRecorder.class)).isNotNull();
            assertThat(context.getBean(WorkflowExecutionAuditRetentionJob.class)).isNotNull();
            assertThat(context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class))
                    .hasSize(1);
        }
    }

    @Configuration
    @EnableScheduling
    @Import({
            WebhookDeliveryDispatcher.class,
            WebhookApprovalOutboxService.class,
            WorkflowExecutionAuditRecorder.class,
            WorkflowExecutionAuditRetentionJob.class
    })
    static class TestConfiguration {

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        WebhookProperties webhookProperties() {
            WebhookProperties properties = new WebhookProperties();
            WebhookProperties.Source source = new WebhookProperties.Source();
            source.setOwnerId(7L);
            source.setAllowedKbIds(Set.of("kb-1"));
            source.setInboundSigningKey("inbound-test-key");
            source.setCallbackUrl("https://callback.example.invalid");
            source.setOutboundSigningKey("outbound-test-key");
            properties.getSources().put("source-7", source);
            return properties;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
