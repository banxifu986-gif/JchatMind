package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.IngestionTaskMapper;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionTaskServiceImplTest {

    @Test
    void shouldCreateQueuedTaskForOwnedKnowledgeBaseAndDocument() {
        IngestionTaskMapper taskMapper = mock(IngestionTaskMapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        when(knowledgeBaseAccessService.requireAccessibleKnowledgeBase("kb-1", "7"))
                .thenReturn(KnowledgeBase.builder().id("kb-1").ownerId("7").build());
        when(documentMapper.selectById("doc-1"))
                .thenReturn(Document.builder().id("doc-1").kbId("kb-1").build());
        when(taskMapper.insert(any(IngestionTask.class))).thenReturn(1);
        ServiceFixture fixture = service(taskMapper, documentMapper, knowledgeBaseAccessService);

        IngestionTask task = submit(fixture.service(), "kb-1", "doc-1", "idempotency-1");

        assertThat(task)
                .extracting(IngestionTask::getOwnerId, IngestionTask::getKbId, IngestionTask::getDocumentId,
                        IngestionTask::getStatus, IngestionTask::getAttemptCount, IngestionTask::getMaxAttempts)
                .containsExactly(7L, "kb-1", "doc-1", "QUEUED", 0, 3);
        verify(taskMapper).insert(task);
        assertThat(mockingDetails(fixture.publisher()).getInvocations())
                .anySatisfy(invocation -> {
                    assertThat(invocation.getMethod().getName()).isEqualTo("publish");
                    assertThat(invocation.getArguments()).containsExactly(task.getId());
                });
    }

    @Test
    void shouldReturnExistingTaskForTheSameOwnerAndIdempotencyKey() {
        IngestionTaskMapper taskMapper = mock(IngestionTaskMapper.class);
        IngestionTask existing = IngestionTask.builder()
                .id("task-1")
                .ownerId(7L)
                .kbId("kb-1")
                .documentId("doc-1")
                .idempotencyKey("idempotency-1")
                .status("SUCCEEDED")
                .build();
        when(taskMapper.selectByOwnerIdAndIdempotencyKey(7L, "idempotency-1")).thenReturn(existing);
        ServiceFixture fixture = service(taskMapper, mock(DocumentMapper.class), mock(KnowledgeBaseAccessService.class));

        IngestionTask task = submit(fixture.service(), "kb-1", "doc-1", "idempotency-1");

        assertThat(task).isSameAs(existing);
        verify(taskMapper, never()).insert(any(IngestionTask.class));
    }

    @Test
    void shouldRejectIdempotencyKeyReuseForAnotherDocumentWithoutCreatingTask() {
        IngestionTaskMapper taskMapper = mock(IngestionTaskMapper.class);
        IngestionTask existing = IngestionTask.builder()
                .id("task-1")
                .ownerId(7L)
                .kbId("kb-1")
                .documentId("doc-1")
                .idempotencyKey("idempotency-1")
                .status("SUCCEEDED")
                .build();
        when(taskMapper.selectByOwnerIdAndIdempotencyKey(7L, "idempotency-1")).thenReturn(existing);
        ServiceFixture fixture = service(taskMapper, mock(DocumentMapper.class), mock(KnowledgeBaseAccessService.class));

        assertThatThrownBy(() -> submit(fixture.service(), "kb-1", "doc-2", "idempotency-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("幂等键");
        verify(taskMapper, never()).insert(any(IngestionTask.class));
    }

    @Test
    void shouldRejectDocumentOutsideOwnedKnowledgeBaseBeforeCreatingTask() {
        IngestionTaskMapper taskMapper = mock(IngestionTaskMapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        when(knowledgeBaseAccessService.requireAccessibleKnowledgeBase("kb-1", "7"))
                .thenReturn(KnowledgeBase.builder().id("kb-1").ownerId("7").build());
        when(documentMapper.selectById("doc-foreign"))
                .thenReturn(Document.builder().id("doc-foreign").kbId("kb-foreign").build());
        ServiceFixture fixture = service(taskMapper, documentMapper, knowledgeBaseAccessService);

        assertThatThrownBy(() -> submit(fixture.service(), "kb-1", "doc-foreign", "idempotency-1"))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问文档");
        verify(taskMapper, never()).insert(any(IngestionTask.class));
    }

    @Test
    void shouldReturnConcurrentExistingTaskWhenOwnerIdempotencyConflictDoesNotInsert() {
        IngestionTaskMapper taskMapper = mock(IngestionTaskMapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        IngestionTask existing = IngestionTask.builder()
                .id("task-1")
                .ownerId(7L)
                .kbId("kb-1")
                .documentId("doc-1")
                .idempotencyKey("idempotency-1")
                .status("QUEUED")
                .build();
        when(taskMapper.selectByOwnerIdAndIdempotencyKey(7L, "idempotency-1"))
                .thenReturn(null, existing);
        when(knowledgeBaseAccessService.requireAccessibleKnowledgeBase("kb-1", "7"))
                .thenReturn(KnowledgeBase.builder().id("kb-1").ownerId("7").build());
        when(documentMapper.selectById("doc-1"))
                .thenReturn(Document.builder().id("doc-1").kbId("kb-1").build());
        when(taskMapper.insert(any(IngestionTask.class))).thenReturn(0);
        ServiceFixture fixture = service(taskMapper, documentMapper, knowledgeBaseAccessService);

        IngestionTask task = submit(fixture.service(), "kb-1", "doc-1", "idempotency-1");

        assertThat(task).isSameAs(existing);
    }

    @Test
    void shouldRejectReadingTaskOwnedByAnotherUserWithoutLeakingItsExistence() {
        IngestionTaskMapper taskMapper = mock(IngestionTaskMapper.class);
        when(taskMapper.selectById("task-foreign"))
                .thenReturn(IngestionTask.builder().id("task-foreign").ownerId(8L).build());
        ServiceFixture fixture = service(taskMapper, mock(DocumentMapper.class), mock(KnowledgeBaseAccessService.class));

        assertThatThrownBy(() -> getTask(fixture.service(), "task-foreign"))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问任务");
    }

    @Test
    void shouldReturnExistingTaskForSameOwnerKnowledgeBaseAndIdempotencyKeyBeforeUploadWrites() {
        IngestionTaskMapper taskMapper = mock(IngestionTaskMapper.class);
        IngestionTask existing = IngestionTask.builder()
                .id("task-1")
                .ownerId(7L)
                .kbId("kb-1")
                .documentId("doc-1")
                .idempotencyKey("upload-key")
                .build();
        when(taskMapper.selectByOwnerIdAndIdempotencyKey(7L, "upload-key")).thenReturn(existing);
        ServiceFixture fixture = service(taskMapper, mock(DocumentMapper.class), mock(KnowledgeBaseAccessService.class));

        IngestionTask task = findExistingTask(fixture.service(), "kb-1", "upload-key");

        assertThat(task).isSameAs(existing);
        assertThat(mockingDetails(taskMapper).getInvocations())
                .anySatisfy(invocation -> {
                    assertThat(invocation.getMethod().getName()).isEqualTo("lockOwnerIdempotencyKey");
                    assertThat(invocation.getArguments()).containsExactly(7L, "upload-key");
                });
    }

    @Test
    void shouldPublishNewTaskOnlyAfterTransactionCommit() {
        IngestionTaskMapper taskMapper = mock(IngestionTaskMapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        when(knowledgeBaseAccessService.requireAccessibleKnowledgeBase("kb-1", "7"))
                .thenReturn(KnowledgeBase.builder().id("kb-1").ownerId("7").build());
        when(documentMapper.selectById("doc-1"))
                .thenReturn(Document.builder().id("doc-1").kbId("kb-1").build());
        when(taskMapper.insert(any(IngestionTask.class))).thenReturn(1);
        ServiceFixture fixture = service(taskMapper, documentMapper, knowledgeBaseAccessService);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            IngestionTask task = submit(fixture.service(), "kb-1", "doc-1", "upload-key");

            assertThat(mockingDetails(fixture.publisher()).getInvocations()).isEmpty();
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            assertThat(mockingDetails(fixture.publisher()).getInvocations())
                    .anySatisfy(invocation -> {
                        assertThat(invocation.getMethod().getName()).isEqualTo("publish");
                        assertThat(invocation.getArguments()).containsExactly(task.getId());
                    });
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void shouldPublishManualRetryOnlyAfterTransactionCommit() {
        IngestionTaskMapper taskMapper = mock(IngestionTaskMapper.class);
        IngestionTask task = IngestionTask.builder()
                .id("task-1")
                .ownerId(7L)
                .status("DEAD_LETTER")
                .attemptCount(3)
                .build();
        when(taskMapper.selectById("task-1")).thenReturn(task);
        when(taskMapper.updateStatusIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);
        ServiceFixture fixture = service(taskMapper, mock(DocumentMapper.class), mock(KnowledgeBaseAccessService.class));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            retryTask(fixture.service(), "task-1");

            assertThat(mockingDetails(fixture.publisher()).getInvocations()).isEmpty();
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            assertThat(mockingDetails(fixture.publisher()).getInvocations())
                    .anySatisfy(invocation -> {
                        assertThat(invocation.getMethod().getName()).isEqualTo("publish");
                        assertThat(invocation.getArguments()).containsExactly("task-1");
                    });
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private ServiceFixture service(
            IngestionTaskMapper taskMapper,
            DocumentMapper documentMapper,
            KnowledgeBaseAccessService knowledgeBaseAccessService
    ) {
        try {
            Class<?> publisherType = Class.forName("com.kama.jchatmind.ingestion.IngestionTaskPublisher");
            Class<?> serviceType = Class.forName("com.kama.jchatmind.service.impl.IngestionTaskServiceImpl");
            RequestScopeData requestScopeData = new RequestScopeData();
            requestScopeData.setUserId(7L);
            Object publisher = mock(publisherType);
            Object service = serviceType.getConstructor(
                    IngestionTaskMapper.class,
                    DocumentMapper.class,
                    KnowledgeBaseAccessService.class,
                    RequestScopeData.class,
                    IngestionTaskStateMachine.class,
                    publisherType
            ).newInstance(
                    taskMapper,
                    documentMapper,
                    knowledgeBaseAccessService,
                    requestScopeData,
                    new IngestionTaskStateMachine(),
                    publisher
            );
            return new ServiceFixture(service, publisher);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 摄入任务服务尚未实现", e);
        }
    }

    private IngestionTask submit(Object service, String kbId, String documentId, String idempotencyKey) {
        try {
            Method submit = service.getClass().getMethod(
                    "submitDocumentIngestion", String.class, String.class, String.class
            );
            return (IngestionTask) submit.invoke(service, kbId, documentId, idempotencyKey);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException exception) {
                throw exception;
            }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 摄入任务提交入口尚未实现", e);
        }
    }

    private IngestionTask getTask(Object service, String taskId) {
        try {
            Method getTask = service.getClass().getMethod("getTask", String.class);
            return (IngestionTask) getTask.invoke(service, taskId);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException exception) {
                throw exception;
            }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 摄入任务查询入口尚未实现", e);
        }
    }

    private IngestionTask findExistingTask(Object service, String kbId, String idempotencyKey) {
        try {
            Method method = service.getClass().getMethod(
                    "findExistingDocumentIngestion", String.class, String.class
            );
            return (IngestionTask) method.invoke(service, kbId, idempotencyKey);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException exception) {
                throw exception;
            }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 上传幂等预检入口尚未实现", e);
        }
    }

    private void retryTask(Object service, String taskId) {
        try {
            service.getClass().getMethod("retryTask", String.class).invoke(service, taskId);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException exception) {
                throw exception;
            }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 任务重试入口尚未实现", e);
        }
    }

    private record ServiceFixture(Object service, Object publisher) {
    }
}
