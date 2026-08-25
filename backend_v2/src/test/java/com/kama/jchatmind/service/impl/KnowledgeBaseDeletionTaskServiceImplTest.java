package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.deletion.KnowledgeBaseDeletionTaskPublisher;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.KnowledgeBaseDeletionTaskMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.model.entity.KnowledgeBaseDeletionAuditRecord;
import com.kama.jchatmind.model.entity.KnowledgeBaseDeletionTask;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseDeletionTaskServiceImplTest {

    @Test
    void shouldCreateAuditedTaskDeleteOwnedKnowledgeBaseAndPublishAfterPersistence() {
        KnowledgeBaseDeletionTaskMapper deletionTaskMapper = mock(KnowledgeBaseDeletionTaskMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = ownedKnowledgeBaseMapper();
        KnowledgeBaseDeletionTaskPublisher deletionTaskPublisher = mock(KnowledgeBaseDeletionTaskPublisher.class);
        when(deletionTaskMapper.insert(any(KnowledgeBaseDeletionTask.class))).thenReturn(1);
        when(deletionTaskMapper.insertAudit(any(KnowledgeBaseDeletionAuditRecord.class))).thenReturn(1);
        when(knowledgeBaseMapper.deleteByIdAndOwnerId("kb-owned", "7")).thenReturn(1);
        KnowledgeBaseDeletionTaskServiceImpl service = service(
                deletionTaskMapper,
                knowledgeBaseMapper,
                deletionTaskPublisher
        );

        KnowledgeBaseDeletionTask task = service.requestDeletion("kb-owned");

        assertThat(task.getOwnerId()).isEqualTo(7L);
        assertThat(task.getKnowledgeBaseId()).isEqualTo("kb-owned");
        assertThat(task.getTaskType()).isEqualTo("KNOWLEDGE_BASE_DELETION");
        assertThat(task.getIdempotencyKey()).isEqualTo("KNOWLEDGE_BASE_DELETION:kb-owned");
        assertThat(task.getInputSnapshot()).isEqualTo("{\"knowledgeBaseId\":\"kb-owned\"}");
        assertThat(task.getResultRef()).isEqualTo("knowledge-base-deletion-task:" + task.getId());
        assertThat(task.getStatus()).isEqualTo("QUEUED");
        assertThat(task.getProgress()).isZero();
        assertThat(task.getAttemptCount()).isZero();
        assertThat(task.getMaxAttempts()).isEqualTo(3);
        ArgumentCaptor<KnowledgeBaseDeletionAuditRecord> auditCaptor = ArgumentCaptor
                .forClass(KnowledgeBaseDeletionAuditRecord.class);
        verify(deletionTaskMapper).insertAudit(auditCaptor.capture());
        assertThat(auditCaptor.getValue().taskId()).isEqualTo(task.getId());
        assertThat(auditCaptor.getValue().ownerId()).isEqualTo(7L);
        assertThat(auditCaptor.getValue().knowledgeBaseId()).isEqualTo("kb-owned");
        assertThat(auditCaptor.getValue().action()).isEqualTo("DELETE_REQUESTED");
        InOrder inOrder = inOrder(deletionTaskMapper, knowledgeBaseMapper, deletionTaskPublisher);
        inOrder.verify(deletionTaskMapper).insert(any(KnowledgeBaseDeletionTask.class));
        inOrder.verify(deletionTaskMapper).insertAudit(any(KnowledgeBaseDeletionAuditRecord.class));
        inOrder.verify(knowledgeBaseMapper).deleteByIdAndOwnerId("kb-owned", "7");
        inOrder.verify(deletionTaskPublisher).publish(task.getId());
    }

    @Test
    void shouldNotDeleteKnowledgeBaseWhenAuditCannotBePersisted() {
        KnowledgeBaseDeletionTaskMapper deletionTaskMapper = mock(KnowledgeBaseDeletionTaskMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = ownedKnowledgeBaseMapper();
        KnowledgeBaseDeletionTaskPublisher deletionTaskPublisher = mock(KnowledgeBaseDeletionTaskPublisher.class);
        when(deletionTaskMapper.insert(any(KnowledgeBaseDeletionTask.class))).thenReturn(1);
        when(deletionTaskMapper.insertAudit(any(KnowledgeBaseDeletionAuditRecord.class))).thenReturn(0);
        KnowledgeBaseDeletionTaskServiceImpl service = service(
                deletionTaskMapper,
                knowledgeBaseMapper,
                deletionTaskPublisher
        );

        assertThatThrownBy(() -> service.requestDeletion("kb-owned"))
                .isInstanceOf(BizException.class)
                .hasMessage("写入知识库删除审计失败");

        verify(knowledgeBaseMapper, never()).deleteByIdAndOwnerId(any(), any());
        verify(deletionTaskPublisher, never()).publish(any());
    }

    @Test
    void shouldRejectLegacyKnowledgeBaseBeforeCreatingDeletionTask() {
        KnowledgeBaseDeletionTaskMapper deletionTaskMapper = mock(KnowledgeBaseDeletionTaskMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        when(knowledgeBaseMapper.selectById("kb-legacy")).thenReturn(KnowledgeBase.builder()
                .id("kb-legacy")
                .build());
        KnowledgeBaseDeletionTaskPublisher deletionTaskPublisher = mock(KnowledgeBaseDeletionTaskPublisher.class);
        KnowledgeBaseDeletionTaskServiceImpl service = service(
                deletionTaskMapper,
                knowledgeBaseMapper,
                deletionTaskPublisher
        );

        assertThatThrownBy(() -> service.requestDeletion("kb-legacy"))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问知识库");

        verify(deletionTaskMapper, never()).insert(any());
        verify(deletionTaskPublisher, never()).publish(any());
    }

    @Test
    void shouldReturnDeletionTaskOnlyToItsOwner() {
        KnowledgeBaseDeletionTaskMapper deletionTaskMapper = mock(KnowledgeBaseDeletionTaskMapper.class);
        KnowledgeBaseDeletionTask ownedTask = KnowledgeBaseDeletionTask.builder()
                .id("task-1")
                .ownerId(7L)
                .status("QUEUED")
                .build();
        when(deletionTaskMapper.selectById("task-1")).thenReturn(ownedTask);
        KnowledgeBaseDeletionTaskServiceImpl service = service(
                deletionTaskMapper,
                mock(KnowledgeBaseMapper.class),
                mock(KnowledgeBaseDeletionTaskPublisher.class)
        );

        assertThat(service.getTask("task-1")).isSameAs(ownedTask);
    }

    @Test
    void shouldHideDeletionTaskOwnedByAnotherUser() {
        KnowledgeBaseDeletionTaskMapper deletionTaskMapper = mock(KnowledgeBaseDeletionTaskMapper.class);
        when(deletionTaskMapper.selectById("task-2")).thenReturn(KnowledgeBaseDeletionTask.builder()
                .id("task-2")
                .ownerId(9L)
                .build());
        KnowledgeBaseDeletionTaskServiceImpl service = service(
                deletionTaskMapper,
                mock(KnowledgeBaseMapper.class),
                mock(KnowledgeBaseDeletionTaskPublisher.class)
        );

        assertThatThrownBy(() -> service.getTask("task-2"))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问知识库删除任务");
    }

    @Test
    void shouldReturnExistingDeletionTaskForRepeatedRequestAfterKnowledgeBaseIsGone() {
        KnowledgeBaseDeletionTaskMapper deletionTaskMapper = mock(KnowledgeBaseDeletionTaskMapper.class);
        KnowledgeBaseDeletionTask existingTask = KnowledgeBaseDeletionTask.builder()
                .id("task-1")
                .ownerId(7L)
                .knowledgeBaseId("kb-owned")
                .idempotencyKey("KNOWLEDGE_BASE_DELETION:kb-owned")
                .status("RETRYING")
                .build();
        when(deletionTaskMapper.selectByOwnerIdAndIdempotencyKey(
                7L,
                "KNOWLEDGE_BASE_DELETION:kb-owned"
        )).thenReturn(existingTask);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseDeletionTaskPublisher deletionTaskPublisher = mock(KnowledgeBaseDeletionTaskPublisher.class);
        KnowledgeBaseDeletionTaskServiceImpl service = service(
                deletionTaskMapper,
                knowledgeBaseMapper,
                deletionTaskPublisher
        );

        KnowledgeBaseDeletionTask task = service.requestDeletion("kb-owned");

        assertThat(task).isSameAs(existingTask);
        verify(deletionTaskMapper).lockOwnerIdempotencyKey(7L, "KNOWLEDGE_BASE_DELETION:kb-owned");
        verify(deletionTaskMapper, never()).insert(any());
        verify(deletionTaskMapper, never()).insertAudit(any());
        verify(knowledgeBaseMapper, never()).deleteByIdAndOwnerId(any(), any());
        verify(deletionTaskPublisher, never()).publish(any());
    }

    @Test
    void shouldClaimQueuedTaskOnlyOnceAndMarkItRunning() {
        KnowledgeBaseDeletionTaskMapper deletionTaskMapper = mock(KnowledgeBaseDeletionTaskMapper.class);
        KnowledgeBaseDeletionTask task = KnowledgeBaseDeletionTask.builder()
                .id("task-1")
                .status("QUEUED")
                .attemptCount(0)
                .maxAttempts(3)
                .build();
        when(deletionTaskMapper.selectById("task-1")).thenReturn(task);
        when(deletionTaskMapper.updateStatusIfCurrent("task-1", "QUEUED", "RUNNING", 0, null)).thenReturn(1);
        KnowledgeBaseDeletionTaskServiceImpl service = service(
                deletionTaskMapper,
                mock(KnowledgeBaseMapper.class),
                mock(KnowledgeBaseDeletionTaskPublisher.class)
        );

        KnowledgeBaseDeletionTask claimed = service.claimTask("task-1");

        assertThat(claimed).isSameAs(task);
        assertThat(task.getStatus()).isEqualTo("RUNNING");
        verify(deletionTaskMapper).updateStatusIfCurrent("task-1", "QUEUED", "RUNNING", 0, null);
    }

    @Test
    void shouldDeadLetterWhenFinalDeletionAttemptFails() {
        KnowledgeBaseDeletionTaskMapper deletionTaskMapper = mock(KnowledgeBaseDeletionTaskMapper.class);
        KnowledgeBaseDeletionTask task = KnowledgeBaseDeletionTask.builder()
                .id("task-1")
                .status("RUNNING")
                .attemptCount(2)
                .maxAttempts(3)
                .build();
        when(deletionTaskMapper.updateStatusIfCurrent(
                "task-1",
                "RUNNING",
                "DEAD_LETTER",
                3,
                "IOException"
        )).thenReturn(1);
        KnowledgeBaseDeletionTaskServiceImpl service = service(
                deletionTaskMapper,
                mock(KnowledgeBaseMapper.class),
                mock(KnowledgeBaseDeletionTaskPublisher.class)
        );

        String status = service.failClaimedTask(task, "IOException");

        assertThat(status).isEqualTo("DEAD_LETTER");
        assertThat(task.getAttemptCount()).isEqualTo(3);
        assertThat(task.getErrorSummary()).isEqualTo("IOException");
    }

    private KnowledgeBaseMapper ownedKnowledgeBaseMapper() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        when(knowledgeBaseMapper.selectById("kb-owned")).thenReturn(KnowledgeBase.builder()
                .id("kb-owned")
                .ownerId("7")
                .build());
        return knowledgeBaseMapper;
    }

    private KnowledgeBaseDeletionTaskServiceImpl service(
            KnowledgeBaseDeletionTaskMapper deletionTaskMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseDeletionTaskPublisher deletionTaskPublisher
    ) {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);
        return new KnowledgeBaseDeletionTaskServiceImpl(
                deletionTaskMapper,
                knowledgeBaseMapper,
                new KnowledgeBaseAccessService(knowledgeBaseMapper),
                requestScopeData,
                deletionTaskPublisher,
                new ObjectMapper()
        );
    }
}
