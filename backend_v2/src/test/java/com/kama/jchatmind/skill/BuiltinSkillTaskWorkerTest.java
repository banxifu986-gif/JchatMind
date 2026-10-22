package com.kama.jchatmind.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.BuiltinSkillTaskMapper;
import com.kama.jchatmind.model.entity.BuiltinSkillExecutionTask;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BuiltinSkillTaskWorkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void revalidatesAndPersistsAClaimedSkillResult() throws Exception {
        BuiltinSkillTaskMapper mapper = mock(BuiltinSkillTaskMapper.class);
        BuiltinSkillExecutionService executionService = mock(BuiltinSkillExecutionService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        BuiltinSkillExecutionTask task = task("task-1", "compare", 1, 5);
        BuiltinSkillExecutionService.PreparedSkillExecution prepared = prepared("compare", List.of("kb-1"));
        BuiltinSkillExecutionService.ExecutionResponse response = new BuiltinSkillExecutionService.ExecutionResponse(
                Map.of("abstained", true, "reason", "no evidence"), true, List.of()
        );
        when(mapper.claimExecutionTask("task-1", 300)).thenReturn(1);
        when(mapper.selectByTaskId("task-1")).thenReturn(task);
        when(executionService.prepare(
                42L, "agent-1", "session-1", "technical-decision-comparison",
                Map.of("question", "compare", "kbIds", List.of("kb-1"))
        )).thenReturn(prepared);
        when(executionService.execute(prepared)).thenReturn(response);
        when(mapper.completeExecutionTask(eq("task-1"), eq(1), anyString())).thenReturn(1);
        BuiltinSkillTaskWorker worker = new BuiltinSkillTaskWorker(
                mapper, executionService, rabbitTemplate, objectMapper
        );

        worker.onMessage("task-1");

        var resultJson = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper).completeExecutionTask(eq("task-1"), eq(1), resultJson.capture());
        assertThat(objectMapper.readTree(resultJson.getValue()).path("reason").asText()).isEqualTo("no evidence");
        verify(executionService).prepare(
                42L, "agent-1", "session-1", "technical-decision-comparison",
                Map.of("question", "compare", "kbIds", List.of("kb-1"))
        );
        verify(executionService).execute(prepared);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void ignoresDuplicateDeliveriesWhenTheTaskIsAlreadyClaimed() {
        BuiltinSkillTaskMapper mapper = mock(BuiltinSkillTaskMapper.class);
        BuiltinSkillExecutionService executionService = mock(BuiltinSkillExecutionService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(mapper.claimExecutionTask("task-1", 300)).thenReturn(0);
        when(mapper.selectByTaskId("task-1")).thenReturn(task("task-1", "compare", 1, 5));
        BuiltinSkillTaskWorker worker = new BuiltinSkillTaskWorker(
                mapper, executionService, rabbitTemplate, objectMapper
        );

        worker.onMessage("\"task-1\"");

        verify(mapper).claimExecutionTask("task-1", 300);
        verifyNoInteractions(executionService, rabbitTemplate);
    }

    @Test
    void marksAuthorizationOrSkillVersionFailuresWithoutRetrying() {
        BuiltinSkillTaskMapper mapper = mock(BuiltinSkillTaskMapper.class);
        BuiltinSkillExecutionService executionService = mock(BuiltinSkillExecutionService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        BuiltinSkillExecutionTask task = task("task-1", "compare", 1, 5);
        BuiltinSkillExecutionService.PreparedSkillExecution wrongVersion = prepared("compare", List.of("kb-1"), "v2");
        when(mapper.claimExecutionTask("task-1", 300)).thenReturn(1);
        when(mapper.selectByTaskId("task-1")).thenReturn(task);
        when(executionService.prepare(
                42L, "agent-1", "session-1", "technical-decision-comparison",
                Map.of("question", "compare", "kbIds", List.of("kb-1"))
        )).thenReturn(wrongVersion);
        when(mapper.failExecutionTask("task-1", 1, "BizException", "任务输入、授权或 Skill 版本校验失败"))
                .thenReturn(1);
        BuiltinSkillTaskWorker worker = new BuiltinSkillTaskWorker(
                mapper, executionService, rabbitTemplate, objectMapper
        );

        worker.onMessage("task-1");

        verify(mapper).failExecutionTask(
                "task-1", 1, "BizException", "任务输入、授权或 Skill 版本校验失败"
        );
        verify(executionService, org.mockito.Mockito.never()).execute(wrongVersion);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void retriesTransientExecutionFailuresThroughTheDedicatedRetryExchange() {
        BuiltinSkillTaskMapper mapper = mock(BuiltinSkillTaskMapper.class);
        BuiltinSkillExecutionService executionService = mock(BuiltinSkillExecutionService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        BuiltinSkillExecutionTask task = task("task-1", "compare", 1, 5);
        BuiltinSkillExecutionService.PreparedSkillExecution prepared = prepared("compare", List.of("kb-1"));
        when(mapper.claimExecutionTask("task-1", 300)).thenReturn(1);
        when(mapper.selectByTaskId("task-1")).thenReturn(task);
        when(executionService.prepare(
                42L, "agent-1", "session-1", "technical-decision-comparison",
                Map.of("question", "compare", "kbIds", List.of("kb-1"))
        )).thenReturn(prepared);
        when(executionService.execute(prepared)).thenThrow(new IllegalStateException("private detail"));
        when(mapper.retryExecutionTask(
                "task-1", 1, "IllegalStateException", "Skill 执行失败，将自动重试", 30
        )).thenReturn(1);
        BuiltinSkillTaskWorker worker = new BuiltinSkillTaskWorker(
                mapper, executionService, rabbitTemplate, objectMapper
        );

        worker.onMessage("task-1");

        verify(rabbitTemplate).convertAndSend(
                RabbitMQConfig.BUILTIN_SKILL_TASK_RETRY_EXCHANGE,
                RabbitMQConfig.BUILTIN_SKILL_TASK_RETRY_ROUTING_KEY,
                "task-1"
        );
    }

    @Test
    void keepsDatabaseRetryStateWhenPublishingToTheRetryExchangeFails() {
        BuiltinSkillTaskMapper mapper = mock(BuiltinSkillTaskMapper.class);
        BuiltinSkillExecutionService executionService = mock(BuiltinSkillExecutionService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        BuiltinSkillExecutionTask task = task("task-1", "compare", 1, 5);
        BuiltinSkillExecutionService.PreparedSkillExecution prepared = prepared("compare", List.of("kb-1"));
        when(mapper.claimExecutionTask("task-1", 300)).thenReturn(1);
        when(mapper.selectByTaskId("task-1")).thenReturn(task);
        when(executionService.prepare(
                42L, "agent-1", "session-1", "technical-decision-comparison",
                Map.of("question", "compare", "kbIds", List.of("kb-1"))
        )).thenReturn(prepared);
        when(executionService.execute(prepared)).thenThrow(new IllegalStateException("execution detail"));
        when(mapper.retryExecutionTask(
                "task-1", 1, "IllegalStateException", "Skill 执行失败，将自动重试", 30
        )).thenReturn(1);
        doThrow(new AmqpException("retry exchange unavailable"))
                .when(rabbitTemplate).convertAndSend(
                        RabbitMQConfig.BUILTIN_SKILL_TASK_RETRY_EXCHANGE,
                        RabbitMQConfig.BUILTIN_SKILL_TASK_RETRY_ROUTING_KEY,
                        "task-1"
                );
        BuiltinSkillTaskWorker worker = new BuiltinSkillTaskWorker(
                mapper, executionService, rabbitTemplate, objectMapper
        );

        assertThatCode(() -> worker.onMessage("task-1")).doesNotThrowAnyException();

        verify(mapper).retryExecutionTask(
                "task-1", 1, "IllegalStateException", "Skill 执行失败，将自动重试", 30
        );
    }

    @Test
    void sendsExhaustedTasksToTheDeadLetterExchange() {
        BuiltinSkillTaskMapper mapper = mock(BuiltinSkillTaskMapper.class);
        BuiltinSkillExecutionService executionService = mock(BuiltinSkillExecutionService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        BuiltinSkillExecutionTask task = task("task-1", "compare", 5, 5);
        BuiltinSkillExecutionService.PreparedSkillExecution prepared = prepared("compare", List.of("kb-1"));
        when(mapper.claimExecutionTask("task-1", 300)).thenReturn(1);
        when(mapper.selectByTaskId("task-1")).thenReturn(task);
        when(executionService.prepare(
                42L, "agent-1", "session-1", "technical-decision-comparison",
                Map.of("question", "compare", "kbIds", List.of("kb-1"))
        )).thenReturn(prepared);
        when(executionService.execute(prepared)).thenThrow(new IllegalStateException("private detail"));
        when(mapper.deadLetterExecutionTask(
                "task-1", 5, "IllegalStateException", "任务已达到最大尝试次数"
        )).thenReturn(1);
        BuiltinSkillTaskWorker worker = new BuiltinSkillTaskWorker(
                mapper, executionService, rabbitTemplate, objectMapper
        );

        worker.onMessage("task-1");

        verify(rabbitTemplate).convertAndSend(
                RabbitMQConfig.BUILTIN_SKILL_TASK_DLX,
                RabbitMQConfig.BUILTIN_SKILL_TASK_DLQ_ROUTING_KEY,
                "task-1"
        );
    }

    private BuiltinSkillExecutionTask task(String taskId, String question, int attemptCount, int maxAttempts) {
        BuiltinSkillExecutionTask task = new BuiltinSkillExecutionTask();
        task.setTaskId(taskId);
        task.setRequesterUserId(42L);
        task.setAgentId("agent-1");
        task.setSessionId("session-1");
        task.setSkillId("technical-decision-comparison");
        task.setSkillVersion("v1");
        task.setInputSnapshot("{\"question\":\"" + question + "\",\"kbIds\":[\"kb-1\"]}");
        task.setAttemptCount(attemptCount);
        task.setMaxAttempts(maxAttempts);
        task.setStatus("RUNNING");
        return task;
    }

    private BuiltinSkillExecutionService.PreparedSkillExecution prepared(String question, List<String> kbIds) {
        return prepared(question, kbIds, "v1");
    }

    private BuiltinSkillExecutionService.PreparedSkillExecution prepared(
            String question,
            List<String> kbIds,
            String version
    ) {
        SkillDefinition definition = new SkillDefinition(
                "technical-decision-comparison", version, "技术方案对比", List.of("question", "kbIds"),
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
