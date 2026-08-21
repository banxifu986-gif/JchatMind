package com.kama.jchatmind.ingestion;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionTaskStateMachineTest {

    @Test
    void shouldMoveQueuedTaskThroughRetryToDeadLetterAfterRetryBudgetIsExhausted() {
        assertThat(successfulTransition("QUEUED", "START")).isEqualTo("RUNNING");
        assertThat(successfulTransition("RUNNING", "RETRY")).isEqualTo("RETRYING");
        assertThat(successfulTransition("RETRYING", "REQUEUE")).isEqualTo("QUEUED");
        assertThat(successfulTransition("RETRYING", "DEAD_LETTER")).isEqualTo("DEAD_LETTER");
        assertThat(successfulTransition("RUNNING", "DEAD_LETTER")).isEqualTo("DEAD_LETTER");
    }

    @Test
    void shouldAllowCancellationOnlyBeforeTaskStarts() {
        assertThat(successfulTransition("QUEUED", "CANCEL")).isEqualTo("CANCELLED");
        assertThat(successfulTransition("RETRYING", "CANCEL")).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> transition("RUNNING", "CANCEL"))
                .hasMessageContaining("不允许");
        assertThatThrownBy(() -> transition("SUCCEEDED", "CANCEL"))
                .hasMessageContaining("不允许");
    }

    @Test
    void shouldAllowManualRetryOnlyForTerminalFailureStates() {
        assertThat(successfulTransition("FAILED", "MANUAL_RETRY")).isEqualTo("QUEUED");
        assertThat(successfulTransition("DEAD_LETTER", "MANUAL_RETRY")).isEqualTo("QUEUED");
        assertThatThrownBy(() -> transition("RUNNING", "MANUAL_RETRY"))
                .hasMessageContaining("不允许");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String transition(String statusName, String eventName) {
        try {
            Class<?> stateMachineType = Class.forName(
                    "com.kama.jchatmind.ingestion.IngestionTaskStateMachine"
            );
            Class<? extends Enum> statusType = (Class<? extends Enum>) Class.forName(
                    "com.kama.jchatmind.ingestion.IngestionTaskStateMachine$Status"
            );
            Class<? extends Enum> eventType = (Class<? extends Enum>) Class.forName(
                    "com.kama.jchatmind.ingestion.IngestionTaskStateMachine$Event"
            );
            Method transition = stateMachineType.getMethod("transition", statusType, eventType);
            Object result = transition.invoke(
                    stateMachineType.getConstructor().newInstance(),
                    Enum.valueOf(statusType, statusName),
                    Enum.valueOf(eventType, eventName)
            );
            return ((Enum<?>) result).name();
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 摄入任务状态机尚未实现", e);
        }
    }

    private String successfulTransition(String statusName, String eventName) {
        try {
            return transition(statusName, eventName);
        } catch (RuntimeException e) {
            throw new AssertionError("期望合法状态迁移被拒绝", e);
        }
    }

    private RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException exception) {
            return exception;
        }
        throw new AssertionError(cause);
    }
}
