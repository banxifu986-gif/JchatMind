package com.kama.jchatmind.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookApprovalOutboxServiceTest {

    @Test
    void shouldPersistApprovalPendingEventOnlyToExplicitOwnerSource() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        WebhookProperties properties = properties(7L);
        WebhookApprovalOutboxService service = new WebhookApprovalOutboxService(jdbcTemplate, properties);

        service.enqueue("source-7", 7L, "00000000-0000-0000-0000-000000000010", "session-1");

        var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        var argsCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertThat(args[2]).isEqualTo("source-7");
        assertThat(sqlCaptor.getValue()).contains("'APPROVAL_PENDING'");
        assertThat(args[3]).isEqualTo("00000000-0000-0000-0000-000000000010");
        JsonNode payload = new ObjectMapper().readTree((String) args[4]);
        assertThat(payload.path("approvalId").asText()).isEqualTo("00000000-0000-0000-0000-000000000010");
        assertThat(payload.path("ownerId").asLong()).isEqualTo(7L);
        assertThat(payload.path("sessionId").asText()).isEqualTo("session-1");
        assertThat((String) args[4]).doesNotContain("toolInput", "query", "claim");
    }

    @Test
    void shouldRejectSourceOwnedByAnotherUserBeforeWritingOutbox() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        WebhookApprovalOutboxService service = new WebhookApprovalOutboxService(jdbcTemplate, properties(8L));

        assertThatThrownBy(() -> service.enqueue(
                "source-7", 7L, "00000000-0000-0000-0000-000000000010", "session-1"
        )).hasMessage("无权访问 Webhook 来源");

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    private WebhookProperties properties(Long ownerId) {
        WebhookProperties properties = new WebhookProperties();
        WebhookProperties.Source source = new WebhookProperties.Source();
        source.setOwnerId(ownerId);
        source.setAllowedKbIds(Set.of("kb-1"));
        source.setInboundSigningKey("inbound-key");
        source.setCallbackUrl("https://callback.example.invalid");
        source.setOutboundSigningKey("outbound-key");
        properties.getSources().put("source-7", source);
        return properties;
    }
}
