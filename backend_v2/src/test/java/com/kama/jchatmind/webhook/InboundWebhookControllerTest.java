package com.kama.jchatmind.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.model.vo.IngestionTaskVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Set;

import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboundWebhookControllerTest {

    private static final String PAYLOAD = "{\"kbId\":\"kb-1\",\"documentId\":\"doc-1\"}";

    @Test
    void shouldVerifyPayloadAndDelegateAtomicTaskInboxSubmission() {
        InboundWebhookVerifier verifier = mock(InboundWebhookVerifier.class);
        WebhookIngressService ingressService = mock(WebhookIngressService.class);
        WebhookProperties properties = properties(Set.of("kb-1"));
        IngestionTask task = IngestionTask.builder()
                .id("task-1")
                .ownerId(7L)
                .kbId("kb-1")
                .documentId("doc-1")
                .status("QUEUED")
                .build();
        when(verifier.verifySignature(any(InboundWebhookEvent.class), any(WebhookSource.class)))
                .thenReturn(WebhookVerificationResult.accepted());
        when(ingressService.submit(eq("build-system"), eq(7L), eq("kb-1"), eq("doc-1"), eq(PAYLOAD),
                any(InboundWebhookEvent.class)))
                .thenReturn(task);
        InboundWebhookController controller = new InboundWebhookController(
                properties,
                verifier,
                ingressService,
                new ObjectMapper()
        );

        ResponseEntity<ApiResponse<IngestionTaskVO>> response = controller.receive(
                "build-system",
                "event-1",
                "1792490400",
                "signature",
                PAYLOAD.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().getData().getTaskId()).isEqualTo("task-1");
        InOrder order = inOrder(verifier, ingressService);
        order.verify(verifier).verifySignature(any(InboundWebhookEvent.class), any(WebhookSource.class));
        ArgumentCaptor<InboundWebhookEvent> event = ArgumentCaptor.forClass(InboundWebhookEvent.class);
        order.verify(ingressService).submit(
                eq("build-system"), eq(7L), eq("kb-1"), eq("doc-1"), eq(PAYLOAD), event.capture()
        );
        assertThat(event.getValue().eventId()).isEqualTo("event-1");
    }

    @Test
    void shouldRejectKnowledgeBaseOutsideSourceWhitelistBeforeCreatingTask() {
        InboundWebhookVerifier verifier = mock(InboundWebhookVerifier.class);
        WebhookIngressService ingressService = mock(WebhookIngressService.class);
        when(verifier.verifySignature(any(InboundWebhookEvent.class), any(WebhookSource.class)))
                .thenReturn(WebhookVerificationResult.accepted());
        InboundWebhookController controller = new InboundWebhookController(
                properties(Set.of("kb-allowed")),
                verifier,
                ingressService,
                new ObjectMapper()
        );

        ResponseEntity<ApiResponse<IngestionTaskVO>> response = controller.receive(
                "build-system", "event-1", "1792490400", "signature", PAYLOAD.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(ingressService, never()).submit(anyString(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void shouldRejectInvalidSignatureWithoutCreatingTaskOrReservingEventId() {
        InboundWebhookVerifier verifier = mock(InboundWebhookVerifier.class);
        WebhookIngressService ingressService = mock(WebhookIngressService.class);
        when(verifier.verifySignature(any(InboundWebhookEvent.class), any(WebhookSource.class)))
                .thenReturn(WebhookVerificationResult.rejected(WebhookVerificationStatus.INVALID_SIGNATURE));
        InboundWebhookController controller = new InboundWebhookController(
                properties(Set.of("kb-1")),
                verifier,
                ingressService,
                new ObjectMapper()
        );

        ResponseEntity<ApiResponse<IngestionTaskVO>> response = controller.receive(
                "build-system", "event-1", "1792490400", "bad-signature", PAYLOAD.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(ingressService, never()).submit(anyString(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void shouldRejectUnknownPayloadFieldsWithoutCreatingTask() {
        InboundWebhookVerifier verifier = mock(InboundWebhookVerifier.class);
        WebhookIngressService ingressService = mock(WebhookIngressService.class);
        when(verifier.verifySignature(any(InboundWebhookEvent.class), any(WebhookSource.class)))
                .thenReturn(WebhookVerificationResult.accepted());
        InboundWebhookController controller = new InboundWebhookController(
                properties(Set.of("kb-1")),
                verifier,
                ingressService,
                new ObjectMapper()
        );

        ResponseEntity<ApiResponse<IngestionTaskVO>> response = controller.receive(
                "build-system",
                "event-1",
                "1792490400",
                "signature",
                "{\"kbId\":\"kb-1\",\"documentId\":\"doc-1\",\"ownerId\":8}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(ingressService, never()).submit(anyString(), any(), anyString(), anyString(), anyString(), any());
    }

    private WebhookProperties properties(Set<String> allowedKbIds) {
        WebhookProperties properties = new WebhookProperties();
        WebhookProperties.Source source = new WebhookProperties.Source();
        source.setOwnerId(7L);
        source.setAllowedKbIds(allowedKbIds);
        source.setInboundSigningKey("inbound-test-key");
        source.setCallbackUrl("https://example.invalid/hook");
        source.setOutboundSigningKey("outbound-test-key");
        properties.getSources().put("build-system", source);
        return properties;
    }
}
