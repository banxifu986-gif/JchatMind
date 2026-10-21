package com.kama.jchatmind.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.TokenInterceptor;
import com.kama.jchatmind.config.WebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class InboundWebhookAuthenticationBoundaryTest {

    @Test
    void shouldRouteSignedWebhookWithoutInvokingJwtInterceptor() throws Exception {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(WebhookTestConfiguration.class);
            context.refresh();
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

            int status = mockMvc.perform(post("/api/webhooks/inbound/build-system")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Webhook-Event-Id", "event-1")
                            .header("X-Webhook-Timestamp", "1792490400")
                            .header("X-Webhook-Signature", "invalid")
                            .content("{\"kbId\":\"kb-1\",\"documentId\":\"doc-1\"}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();

            assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            verify(context.getBean(TokenInterceptor.class), never()).preHandle(any(), any(), any());
        }
    }

    @Configuration
    @EnableWebMvc
    @Import({WebConfig.class, InboundWebhookController.class})
    static class WebhookTestConfiguration {

        @Bean
        TokenInterceptor tokenInterceptor() {
            return mock(TokenInterceptor.class);
        }

        @Bean
        WebhookProperties webhookProperties() {
            WebhookProperties properties = new WebhookProperties();
            WebhookProperties.Source source = new WebhookProperties.Source();
            source.setOwnerId(7L);
            source.setAllowedKbIds(Set.of("kb-1"));
            source.setInboundSigningKey("inbound-test-key");
            source.setCallbackUrl("https://example.invalid/hook");
            source.setOutboundSigningKey("outbound-test-key");
            properties.getSources().put("build-system", source);
            return properties;
        }

        @Bean
        InboundWebhookVerifier inboundWebhookVerifier() {
            InboundWebhookVerifier verifier = mock(InboundWebhookVerifier.class);
            when(verifier.verifySignature(any(InboundWebhookEvent.class), any(WebhookSource.class)))
                    .thenReturn(WebhookVerificationResult.rejected(WebhookVerificationStatus.INVALID_SIGNATURE));
            return verifier;
        }

        @Bean
        WebhookIngressService webhookIngressService() {
            return mock(WebhookIngressService.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
