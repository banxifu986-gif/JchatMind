package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.IngestionTaskMapper;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.impl.IngestionTaskServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionTaskLifecycleTest {

    @Test
    void shouldCancelOwnedQueuedTaskWithConditionalStatusUpdate() {
        IngestionTaskMapper mapper = mock(IngestionTaskMapper.class);
        IngestionTask task = task("QUEUED", 0, 3);
        when(mapper.selectById("task-1")).thenReturn(task);
        when(mapper.updateStatusIfCurrent("task-1", "QUEUED", "CANCELLED", 0, null)).thenReturn(1);
        IngestionTaskServiceImpl service = service(mapper, mock(IngestionTaskPublisher.class));

        invoke(service, "cancelTask", "task-1");

        verify(mapper).updateStatusIfCurrent("task-1", "QUEUED", "CANCELLED", 0, null);
    }

    @Test
    void shouldRejectCancellingCompletedTaskWithoutWritingState() {
        IngestionTaskMapper mapper = mock(IngestionTaskMapper.class);
        when(mapper.selectById("task-1")).thenReturn(task("SUCCEEDED", 0, 3));
        IngestionTaskServiceImpl service = service(mapper, mock(IngestionTaskPublisher.class));

        assertThatThrownBy(() -> invoke(service, "cancelTask", "task-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不允许");
        verify(mapper, never()).updateStatusIfCurrent(
                eq("task-1"), eq("SUCCEEDED"), eq("CANCELLED"), eq(0), eq(null)
        );
    }

    @Test
    void shouldRetryOwnedFailedTaskFromQueuedStateAndPublishItAgain() {
        IngestionTaskMapper mapper = mock(IngestionTaskMapper.class);
        IngestionTaskPublisher publisher = mock(IngestionTaskPublisher.class);
        IngestionTask task = task("FAILED", 3, 3);
        when(mapper.selectById("task-1")).thenReturn(task);
        when(mapper.updateStatusIfCurrent("task-1", "FAILED", "QUEUED", 0, null)).thenReturn(1);
        IngestionTaskServiceImpl service = service(mapper, publisher);

        invoke(service, "retryTask", "task-1");

        verify(mapper).updateStatusIfCurrent("task-1", "FAILED", "QUEUED", 0, null);
        verify(publisher).publish("task-1");
    }

    @Test
    void shouldClaimQueuedTaskOnceAndRecordWorkerFailureAsRetrying() {
        IngestionTaskMapper mapper = mock(IngestionTaskMapper.class);
        IngestionTask queued = task("QUEUED", 0, 3);
        when(mapper.selectById("task-1")).thenReturn(queued);
        when(mapper.updateStatusIfCurrent("task-1", "QUEUED", "RUNNING", 0, null)).thenReturn(1);
        when(mapper.updateStatusIfCurrent("task-1", "RUNNING", "RETRYING", 1, "parse failed"))
                .thenReturn(1);
        IngestionTaskServiceImpl service = service(mapper, mock(IngestionTaskPublisher.class));

        IngestionTask claimed = (IngestionTask) invoke(service, "claimTask", "task-1");
        assertThat(claimed.getStatus()).isEqualTo("RUNNING");
        IngestionTaskStateMachine.Status result = (IngestionTaskStateMachine.Status) invoke(
                service, "failClaimedTask", claimed, "parse failed"
        );

        assertThat(claimed.getStatus()).isEqualTo("RETRYING");
        assertThat(result).isEqualTo(IngestionTaskStateMachine.Status.RETRYING);
        verify(mapper).updateStatusIfCurrent("task-1", "QUEUED", "RUNNING", 0, null);
        verify(mapper).updateStatusIfCurrent("task-1", "RUNNING", "RETRYING", 1, "parse failed");
    }

    @Test
    void shouldCompleteClaimedTaskWithConditionalRunningTransition() {
        IngestionTaskMapper mapper = mock(IngestionTaskMapper.class);
        IngestionTask task = task("RUNNING", 0, 3);
        when(mapper.updateStatusIfCurrent("task-1", "RUNNING", "SUCCEEDED", 0, null)).thenReturn(1);
        IngestionTaskServiceImpl service = service(mapper, mock(IngestionTaskPublisher.class));

        invoke(service, "completeClaimedTask", task);

        assertThat(task.getStatus()).isEqualTo("SUCCEEDED");
        verify(mapper).updateStatusIfCurrent("task-1", "RUNNING", "SUCCEEDED", 0, null);
    }

    @Test
    void shouldMoveClaimedTaskToDeadLetterWhenRetryBudgetIsExhausted() {
        IngestionTaskMapper mapper = mock(IngestionTaskMapper.class);
        IngestionTask task = task("RUNNING", 2, 3);
        when(mapper.updateStatusIfCurrent("task-1", "RUNNING", "DEAD_LETTER", 3, "parse failed"))
                .thenReturn(1);
        IngestionTaskServiceImpl service = service(mapper, mock(IngestionTaskPublisher.class));

        IngestionTaskStateMachine.Status result = (IngestionTaskStateMachine.Status) invoke(
                service, "failClaimedTask", task, "parse failed"
        );

        assertThat(result).isEqualTo(IngestionTaskStateMachine.Status.DEAD_LETTER);
        assertThat(task.getStatus()).isEqualTo("DEAD_LETTER");
        verify(mapper).updateStatusIfCurrent("task-1", "RUNNING", "DEAD_LETTER", 3, "parse failed");
    }

    private IngestionTaskServiceImpl service(IngestionTaskMapper mapper, IngestionTaskPublisher publisher) {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);
        return new IngestionTaskServiceImpl(
                mapper,
                mock(DocumentMapper.class),
                mock(KnowledgeBaseAccessService.class),
                requestScopeData,
                new IngestionTaskStateMachine(),
                publisher
        );
    }

    private IngestionTask task(String status, int attemptCount, int maxAttempts) {
        return IngestionTask.builder()
                .id("task-1")
                .ownerId(7L)
                .kbId("kb-1")
                .documentId("doc-1")
                .status(status)
                .attemptCount(attemptCount)
                .maxAttempts(maxAttempts)
                .build();
    }

    private Object invoke(IngestionTaskServiceImpl service, String methodName, Object... arguments) {
        try {
            Class<?>[] parameterTypes = java.util.Arrays.stream(arguments)
                    .map(argument -> argument instanceof IngestionTask ? IngestionTask.class : String.class)
                    .toArray(Class<?>[]::new);
            Method method = service.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(service, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException exception) {
                throw exception;
            }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 任务生命周期入口尚未实现: " + methodName, e);
        }
    }
}
