package com.kama.jchatmind.webhook;

import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.service.impl.IngestionTaskServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookIngressServiceTest {

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldPersistTaskAndInboxMappingBeforeReservingRedisEventIdAfterCommit() {
        IngestionTaskServiceImpl taskService = mock(IngestionTaskServiceImpl.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        InboundWebhookVerifier verifier = mock(InboundWebhookVerifier.class);
        IngestionTask task = IngestionTask.builder().id("task-1").ownerId(7L).kbId("kb-1").documentId("doc-1").build();
        when(taskService.submitDocumentIngestionForOwner(
                7L, "kb-1", "doc-1", "webhook:" + RedisWebhookEventIdRegistry.eventIdentityHash("source-1", "event-1")
        )).thenReturn(task);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        WebhookIngressService service = new WebhookIngressService(taskService, jdbcTemplate, verifier);
        InboundWebhookEvent event = new InboundWebhookEvent(
                "source-1", "event-1", Instant.parse("2026-10-21T00:00:00Z"), "signature", "payload"
        );

        IngestionTask result = service.submit(
                "source-1", 7L, "kb-1", "doc-1", "payload", event
        );

        assertThat(result).isSameAs(task);
        verify(jdbcTemplate).update(anyString(), any(Object[].class));
        verify(verifier, never()).reserveVerifiedEventId(any());
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(verifier).reserveVerifiedEventId(event);

        var order = inOrder(taskService, jdbcTemplate);
        order.verify(taskService).submitDocumentIngestionForOwner(
                7L,
                "kb-1",
                "doc-1",
                "webhook:" + RedisWebhookEventIdRegistry.eventIdentityHash("source-1", "event-1")
        );
        order.verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }
}
