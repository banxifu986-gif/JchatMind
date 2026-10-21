package com.kama.jchatmind.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.entity.IngestionTask;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookDeliveryEventPayloadFactoryTest {

    @Test
    void shouldBuildFixedMetadataPayloadAndHashWithoutTaskErrorDetails() throws Exception {
        IngestionTask task = IngestionTask.builder()
                .id("00000000-0000-0000-0000-000000000001")
                .ownerId(7L)
                .kbId("00000000-0000-0000-0000-000000000002")
                .documentId("00000000-0000-0000-0000-000000000003")
                .status("DEAD_LETTER")
                .errorSummary("secret path and provider response")
                .build();

        WebhookDeliveryEventPayloadFactory.Payload payload =
                WebhookDeliveryEventPayloadFactory.create(task, "INGESTION_FAILED");

        JsonNode json = new ObjectMapper().readTree(payload.json());
        assertThat(json.path("eventId").asText()).isEqualTo(payload.callbackEventId().toString());
        assertThat(json.path("eventType").asText()).isEqualTo("INGESTION_FAILED");
        assertThat(json.path("taskId").asText()).isEqualTo(task.getId());
        assertThat(json.path("status").asText()).isEqualTo("DEAD_LETTER");
        assertThat(json.path("kbId").asText()).isEqualTo(task.getKbId());
        assertThat(json.path("documentId").asText()).isEqualTo(task.getDocumentId());
        assertThat(payload.json()).doesNotContain("secret path", "provider response");
        assertThat(payload.sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void shouldCreateApprovalPendingPayloadWithOnlyOwnerAndApprovalMetadata() throws Exception {
        var payload = WebhookDeliveryEventPayloadFactory.createApprovalPending(
                "00000000-0000-0000-0000-000000000010",
                7L,
                "session-1"
        );

        JsonNode json = new ObjectMapper().readTree(payload.json());
        assertThat(json.path("eventId").asText()).isEqualTo(payload.callbackEventId().toString());
        assertThat(json.path("eventType").asText()).isEqualTo("APPROVAL_PENDING");
        assertThat(json.path("approvalId").asText()).isEqualTo("00000000-0000-0000-0000-000000000010");
        assertThat(json.path("ownerId").asLong()).isEqualTo(7L);
        assertThat(json.path("sessionId").asText()).isEqualTo("session-1");
        assertThat(payload.json()).doesNotContain("toolInput", "claim", "query");
        assertThat(payload.sha256()).matches("[0-9a-f]{64}");
    }
}
