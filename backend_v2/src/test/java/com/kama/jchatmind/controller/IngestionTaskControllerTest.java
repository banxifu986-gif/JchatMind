package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.service.impl.IngestionTaskServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionTaskControllerTest {

    @Test
    void shouldReturnTaskProgressWithoutOwnerOrIdempotencyKey() throws Exception {
        IngestionTaskServiceImpl ingestionTaskService = mock(IngestionTaskServiceImpl.class);
        when(ingestionTaskService.getTask("task-1")).thenReturn(IngestionTask.builder()
                .id("task-1")
                .ownerId(7L)
                .kbId("kb-1")
                .documentId("doc-1")
                .idempotencyKey("upload-key")
                .status("RUNNING")
                .attemptCount(1)
                .maxAttempts(3)
                .createdAt(LocalDateTime.of(2026, 8, 18, 12, 0))
                .build());
        Object controller = controller(ingestionTaskService);

        Object response = invoke(controller, "getTask", "task-1");
        Object task = response.getClass().getMethod("getData").invoke(response);

        assertThat(taskProperty(task, "getTaskId")).isEqualTo("task-1");
        assertThat(taskProperty(task, "getStatus")).isEqualTo("RUNNING");
        assertThatThrownBy(() -> task.getClass().getMethod("getOwnerId"))
                .isInstanceOf(NoSuchMethodException.class);
        assertThatThrownBy(() -> task.getClass().getMethod("getIdempotencyKey"))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void shouldDelegateTaskCancellationToOwnerCheckedService() throws Exception {
        IngestionTaskServiceImpl ingestionTaskService = mock(IngestionTaskServiceImpl.class);
        Object controller = controller(ingestionTaskService);

        invoke(controller, "cancelTask", "task-1");

        verify(ingestionTaskService).cancelTask("task-1");
    }

    @Test
    void shouldDelegateTaskRetryToOwnerCheckedService() throws Exception {
        IngestionTaskServiceImpl ingestionTaskService = mock(IngestionTaskServiceImpl.class);
        Object controller = controller(ingestionTaskService);

        invoke(controller, "retryTask", "task-1");

        verify(ingestionTaskService).retryTask("task-1");
    }

    private Object controller(IngestionTaskServiceImpl ingestionTaskService) {
        try {
            Class<?> controllerType = Class.forName("com.kama.jchatmind.controller.IngestionTaskController");
            return controllerType.getConstructor(IngestionTaskServiceImpl.class)
                    .newInstance(ingestionTaskService);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 摄入任务控制器尚未实现", e);
        }
    }

    private Object invoke(Object controller, String methodName, String taskId) throws Exception {
        try {
            Method method = controller.getClass().getMethod(methodName, String.class);
            return method.invoke(controller, taskId);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 摄入任务控制器入口尚未实现", e);
        }
    }

    private Object taskProperty(Object task, String getterName) throws Exception {
        return task.getClass().getMethod(getterName).invoke(task);
    }
}
