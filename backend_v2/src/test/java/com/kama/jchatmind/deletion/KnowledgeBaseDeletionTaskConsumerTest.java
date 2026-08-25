package com.kama.jchatmind.deletion;

import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.model.entity.KnowledgeBaseDeletionTask;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.impl.KnowledgeBaseDeletionTaskServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseDeletionTaskConsumerTest {

    @Test
    void shouldClearKnowledgeBaseDirectoryAndCompleteClaimedTask() throws Exception {
        KnowledgeBaseDeletionTaskServiceImpl deletionTaskService = mock(KnowledgeBaseDeletionTaskServiceImpl.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        KnowledgeBaseDeletionTask task = runningTask();
        when(deletionTaskService.claimTask("task-1")).thenReturn(task);
        KnowledgeBaseDeletionTaskConsumer consumer = new KnowledgeBaseDeletionTaskConsumer(
                deletionTaskService,
                documentStorageService,
                rabbitTemplate
        );

        consumer.onMessage("\"task-1\"");

        verify(documentStorageService).deleteKnowledgeBaseDirectory("kb-owned");
        verify(deletionTaskService).completeClaimedTask(task);
    }

    @Test
    void shouldPublishRetryAfterDirectoryCleanupFailure() throws Exception {
        KnowledgeBaseDeletionTaskServiceImpl deletionTaskService = mock(KnowledgeBaseDeletionTaskServiceImpl.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        KnowledgeBaseDeletionTask task = runningTask();
        when(deletionTaskService.claimTask("task-1")).thenReturn(task);
        doThrow(new IOException("locked")).when(documentStorageService).deleteKnowledgeBaseDirectory("kb-owned");
        when(deletionTaskService.failClaimedTask(task, "IOException")).thenReturn("RETRYING");
        KnowledgeBaseDeletionTaskConsumer consumer = new KnowledgeBaseDeletionTaskConsumer(
                deletionTaskService,
                documentStorageService,
                rabbitTemplate
        );

        consumer.onMessage("task-1");

        verify(rabbitTemplate).convertAndSend(
                RabbitMQConfig.KNOWLEDGE_BASE_DELETION_RETRY_EXCHANGE,
                RabbitMQConfig.KNOWLEDGE_BASE_DELETION_RETRY_ROUTING_KEY,
                "task-1"
        );
    }

    @Test
    void shouldPublishDeadLetterAfterFinalDirectoryCleanupFailure() throws Exception {
        KnowledgeBaseDeletionTaskServiceImpl deletionTaskService = mock(KnowledgeBaseDeletionTaskServiceImpl.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        KnowledgeBaseDeletionTask task = runningTask();
        when(deletionTaskService.claimTask("task-1")).thenReturn(task);
        doThrow(new IOException("locked")).when(documentStorageService).deleteKnowledgeBaseDirectory("kb-owned");
        when(deletionTaskService.failClaimedTask(task, "IOException")).thenReturn("DEAD_LETTER");
        KnowledgeBaseDeletionTaskConsumer consumer = new KnowledgeBaseDeletionTaskConsumer(
                deletionTaskService,
                documentStorageService,
                rabbitTemplate
        );

        consumer.onMessage("task-1");

        verify(rabbitTemplate).convertAndSend(
                RabbitMQConfig.KNOWLEDGE_BASE_DELETION_DLX,
                RabbitMQConfig.KNOWLEDGE_BASE_DELETION_DLQ_ROUTING_KEY,
                "task-1"
        );
    }

    private KnowledgeBaseDeletionTask runningTask() {
        return KnowledgeBaseDeletionTask.builder()
                .id("task-1")
                .knowledgeBaseId("kb-owned")
                .status("RUNNING")
                .build();
    }
}
