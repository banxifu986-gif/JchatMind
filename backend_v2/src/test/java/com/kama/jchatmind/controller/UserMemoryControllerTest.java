package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

class UserMemoryControllerTest {

    @Test
    void shouldExposeCandidateConfirmationEndpoint() throws Exception {
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        UserMemoryController controller = new UserMemoryController(userMemoryFacadeService);

        Method method = confirmationMethod();
        ApiResponse<?> response = invoke(method, controller, "candidate-1");

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/memory-candidates/{candidateId}/confirm");
        assertThat(response.getCode()).isEqualTo(200);
        assertThat(mockingDetails(userMemoryFacadeService).getInvocations())
                .extracting(invocation -> invocation.getMethod().getName())
                .contains("confirmUserMemoryCandidate");
    }

    @Test
    void shouldExposeCandidateDiscardEndpoint() throws Exception {
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        UserMemoryController controller = new UserMemoryController(userMemoryFacadeService);

        Method method = discardMethod();
        ApiResponse<?> response = invoke(method, controller, "candidate-1");

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/memory-candidates/{candidateId}/discard");
        assertThat(response.getCode()).isEqualTo(200);
        assertThat(mockingDetails(userMemoryFacadeService).getInvocations())
                .extracting(invocation -> invocation.getMethod().getName())
                .contains("discardUserMemoryCandidate");
    }

    @Test
    void shouldExposeClearMemoriesEndpoint() throws Exception {
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        UserMemoryController controller = new UserMemoryController(userMemoryFacadeService);

        Method method = clearMemoriesMethod();
        ApiResponse<?> response = invoke(method, controller);

        assertThat(method.getAnnotation(DeleteMapping.class).value())
                .containsExactly("/memories");
        assertThat(response.getCode()).isEqualTo(200);
        assertThat(mockingDetails(userMemoryFacadeService).getInvocations())
                .extracting(invocation -> invocation.getMethod().getName())
                .contains("clearUserMemories");
    }

    @Test
    void shouldExposeMemoryEditingEndpoint() throws Exception {
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        UserMemoryController controller = new UserMemoryController(userMemoryFacadeService);

        Method method = updateMemoryMethod();
        ApiResponse<?> response = invoke(method, controller, "memory-1", newUpdateMemoryRequest("新记忆"));

        assertThat(method.getAnnotation(PatchMapping.class).value())
                .containsExactly("/memories/{memoryId}");
        assertThat(response.getCode()).isEqualTo(200);
        assertThat(mockingDetails(userMemoryFacadeService).getInvocations()).anySatisfy(invocation -> {
            assertThat(invocation.getMethod().getName()).isEqualTo("updateMemory");
            assertThat(invocation.getArguments()).containsExactly("memory-1", "新记忆");
        });
    }

    @Test
    void shouldExposeMemoryExpirationEndpoint() throws Exception {
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        UserMemoryController controller = new UserMemoryController(userMemoryFacadeService);
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(1).withNano(0);

        Method method = updateMemoryExpirationMethod();
        ApiResponse<?> response = invoke(
                method,
                controller,
                "memory-1",
                newUpdateMemoryExpirationRequest(expiresAt)
        );

        assertThat(method.getAnnotation(PatchMapping.class).value())
                .containsExactly("/memories/{memoryId}/expiration");
        assertThat(response.getCode()).isEqualTo(200);
        assertThat(mockingDetails(userMemoryFacadeService).getInvocations()).anySatisfy(invocation -> {
            assertThat(invocation.getMethod().getName()).isEqualTo("updateMemoryExpiration");
            assertThat(invocation.getArguments()).containsExactly("memory-1", expiresAt);
        });
    }

    private Method confirmationMethod() {
        try {
            return UserMemoryController.class.getMethod("confirmUserMemoryCandidate", String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("候选记忆确认 Controller 入口尚未实现", e);
        }
    }

    private Method discardMethod() {
        try {
            return UserMemoryController.class.getMethod("discardUserMemoryCandidate", String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("候选记忆忽略 Controller 入口尚未实现", e);
        }
    }

    private Method clearMemoriesMethod() {
        try {
            return UserMemoryController.class.getMethod("clearUserMemories");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("清空用户长期记忆 Controller 入口尚未实现", e);
        }
    }

    private Method updateMemoryMethod() {
        try {
            return UserMemoryController.class.getMethod(
                    "updateMemory",
                    String.class,
                    Class.forName("com.kama.jchatmind.model.request.UpdateUserMemoryRequest")
            );
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new AssertionError("用户长期记忆编辑 Controller 入口尚未实现", e);
        }
    }

    private Method updateMemoryExpirationMethod() {
        try {
            return UserMemoryController.class.getMethod(
                    "updateMemoryExpiration",
                    String.class,
                    Class.forName("com.kama.jchatmind.model.request.UpdateUserMemoryExpirationRequest")
            );
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new AssertionError("用户记忆过期时间 Controller 入口尚未实现", e);
        }
    }

    private Object newUpdateMemoryRequest(String content) {
        try {
            Class<?> requestType = Class.forName("com.kama.jchatmind.model.request.UpdateUserMemoryRequest");
            Object request = requestType.getConstructor().newInstance();
            requestType.getMethod("setContent", String.class).invoke(request, content);
            return request;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("用户长期记忆编辑请求尚未实现", e);
        }
    }

    private Object newUpdateMemoryExpirationRequest(LocalDateTime expiresAt) {
        try {
            Class<?> requestType = Class.forName(
                    "com.kama.jchatmind.model.request.UpdateUserMemoryExpirationRequest"
            );
            Object request = requestType.getConstructor().newInstance();
            requestType.getMethod("setExpiresAt", LocalDateTime.class).invoke(request, expiresAt);
            return request;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("用户记忆过期时间请求尚未实现", e);
        }
    }

    private ApiResponse<?> invoke(Method method, UserMemoryController controller, String candidateId) {
        try {
            return (ApiResponse<?>) method.invoke(controller, candidateId);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private ApiResponse<?> invoke(Method method, UserMemoryController controller) {
        try {
            return (ApiResponse<?>) method.invoke(controller);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private ApiResponse<?> invoke(Method method, UserMemoryController controller, String memoryId, Object request) {
        try {
            return (ApiResponse<?>) method.invoke(controller, memoryId, request);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
