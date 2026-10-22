package com.kama.jchatmind.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.BuiltinSkillTaskMapper;
import com.kama.jchatmind.model.entity.BuiltinSkillExecutionTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuiltinSkillTaskServiceTest {

    @Test
    void taskMapperSupportsDueTaskSelectionAndAtomicDispatchLeases() {
        assertThat(Arrays.stream(BuiltinSkillTaskMapper.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName))
                .contains(
                        "selectDispatchableTaskIds",
                        "acquireDispatchLease",
                        "selectByTaskId",
                        "claimExecutionTask",
                        "completeExecutionTask",
                        "retryExecutionTask",
                        "deadLetterExecutionTask",
                        "recoverExpiredExecutionTasks"
                );
    }

    @Test
    void persistsTheAuthorizedSkillSnapshotAndVersionForAsyncExecution() throws Exception {
        BuiltinSkillExecutionService executionService = mock(BuiltinSkillExecutionService.class);
        BuiltinSkillTaskMapper mapper = mock(BuiltinSkillTaskMapper.class);
        when(executionService.prepare(
                42L, "agent-1", "session-1", "technical-decision-comparison", input("compare", "kb-2")
        )).thenReturn(prepared("compare", List.of("kb-2")));
        when(mapper.insertIfAbsent(any())).thenReturn(1);
        BuiltinSkillTaskService service = new BuiltinSkillTaskService(
                executionService, mapper, new ObjectMapper()
        );

        BuiltinSkillTaskService.SubmissionResult result = service.submit(
                42L, "agent-1", "session-1", "technical-decision-comparison", "request-key-1",
                input("compare", "kb-2")
        );

        assertThat(result.taskId()).isNotBlank();
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.replayed()).isFalse();
        assertThat(result.resultReference()).isEqualTo("/api/skill-tasks/" + result.taskId());
        var task = org.mockito.ArgumentCaptor.forClass(BuiltinSkillExecutionTask.class);
        org.mockito.Mockito.verify(mapper).insertIfAbsent(task.capture());
        assertThat(task.getValue().getRequesterUserId()).isEqualTo(42L);
        assertThat(task.getValue().getAgentId()).isEqualTo("agent-1");
        assertThat(task.getValue().getSessionId()).isEqualTo("session-1");
        assertThat(task.getValue().getSkillVersion()).isEqualTo("v1");
        assertThat(task.getValue().getInputSnapshot()).contains("compare", "kb-2");
        assertThat(task.getValue().getRequestSha256()).matches("[0-9a-f]{64}");
        assertThat(task.getValue().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void returnsTheOriginalTaskWhenTheSameOwnerRepeatsAnIdenticalIdempotencyKey() throws Exception {
        BuiltinSkillExecutionService executionService = mock(BuiltinSkillExecutionService.class);
        BuiltinSkillTaskMapper mapper = mock(BuiltinSkillTaskMapper.class);
        when(executionService.prepare(any(), any(), any(), any(), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> input = invocation.getArgument(4);
            return prepared((String) input.get("question"), (List<String>) input.get("kbIds"));
        });
        AtomicInteger insertCalls = new AtomicInteger();
        AtomicReference<BuiltinSkillExecutionTask> storedTask = new AtomicReference<>();
        when(mapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            BuiltinSkillExecutionTask task = invocation.getArgument(0);
            if (insertCalls.getAndIncrement() == 0) {
                storedTask.set(task);
                return 1;
            }
            return 0;
        });
        when(mapper.selectByRequesterAndIdempotencyKey(42L, "request-key-1"))
                .thenAnswer(invocation -> storedTask.get());
        BuiltinSkillTaskService service = new BuiltinSkillTaskService(
                executionService, mapper, new ObjectMapper()
        );

        BuiltinSkillTaskService.SubmissionResult first = service.submit(
                42L, "agent-1", "session-1", "technical-decision-comparison", "request-key-1",
                Map.of("question", "compare", "kbIds", List.of("kb-2", "kb-1"))
        );
        BuiltinSkillTaskService.SubmissionResult replay = service.submit(
                42L, "agent-1", "session-1", "technical-decision-comparison", "request-key-1",
                Map.of("question", "compare", "kbIds", List.of("kb-1", "kb-2"))
        );

        assertThat(replay.taskId()).isEqualTo(first.taskId());
        assertThat(replay.replayed()).isTrue();
        assertThat(insertCalls.get()).isEqualTo(2);
    }

    @Test
    void rejectsReusingAnIdempotencyKeyForDifferentSkillInput() throws Exception {
        BuiltinSkillExecutionService executionService = mock(BuiltinSkillExecutionService.class);
        BuiltinSkillTaskMapper mapper = mock(BuiltinSkillTaskMapper.class);
        when(executionService.prepare(any(), any(), any(), any(), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> input = invocation.getArgument(4);
            return prepared((String) input.get("question"), List.of("kb-2"));
        });
        AtomicInteger insertCalls = new AtomicInteger();
        AtomicReference<BuiltinSkillExecutionTask> storedTask = new AtomicReference<>();
        when(mapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            BuiltinSkillExecutionTask task = invocation.getArgument(0);
            if (insertCalls.getAndIncrement() == 0) {
                storedTask.set(task);
                return 1;
            }
            return 0;
        });
        when(mapper.selectByRequesterAndIdempotencyKey(42L, "request-key-1"))
                .thenAnswer(invocation -> storedTask.get());
        BuiltinSkillTaskService service = new BuiltinSkillTaskService(
                executionService, mapper, new ObjectMapper()
        );
        service.submit(42L, "agent-1", "session-1", "technical-decision-comparison", "request-key-1",
                input("compare A", "kb-2"));

        assertThatThrownBy(() -> service.submit(
                42L, "agent-1", "session-1", "technical-decision-comparison", "request-key-1",
                input("compare B", "kb-2")
        )).isInstanceOf(BizException.class)
                .hasMessage("幂等键已用于不同任务请求");
    }

    @Test
    void doesNotReturnAnotherOwnersTask() {
        BuiltinSkillExecutionService executionService = mock(BuiltinSkillExecutionService.class);
        BuiltinSkillTaskMapper mapper = mock(BuiltinSkillTaskMapper.class);
        when(mapper.selectByIdAndRequester("task-1", 84L)).thenReturn(null);
        BuiltinSkillTaskService service = new BuiltinSkillTaskService(
                executionService, mapper, new ObjectMapper()
        );

        assertThatThrownBy(() -> service.getOwnedTask("task-1", 84L))
                .isInstanceOf(BizException.class)
                .hasMessage("任务不存在或无权访问");
    }

    private Map<String, Object> input(String question, String kbId) {
        return Map.of("question", question, "kbIds", List.of(kbId));
    }

    private BuiltinSkillExecutionService.PreparedSkillExecution prepared(String question, List<String> kbIds) {
        SkillDefinition definition = new SkillDefinition(
                "technical-decision-comparison", "v1", "技术方案对比", List.of("question", "kbIds"),
                List.of("KnowledgeTool"), SkillExecutionMode.SYNC, 30, 2, false
        );
        PreparedSkillInvocation invocation = new PreparedSkillInvocation(
                definition,
                Map.of("question", question, "kbIds", kbIds),
                kbIds
        );
        return new BuiltinSkillExecutionService.PreparedSkillExecution(
                "42", "agent-1", "session-1", "technical-decision-comparison", invocation
        );
    }
}
