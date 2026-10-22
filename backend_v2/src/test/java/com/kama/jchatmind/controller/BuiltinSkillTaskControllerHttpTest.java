package com.kama.jchatmind.controller;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.GlobalExceptionHandler;
import com.kama.jchatmind.model.entity.BuiltinSkillExecutionTask;
import com.kama.jchatmind.skill.BuiltinSkillTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BuiltinSkillTaskControllerHttpTest {

    @Test
    void submitsTasksAndReturnsOwnerScopedTaskStatus() throws Exception {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(42L);
        BuiltinSkillTaskService taskService = mock(BuiltinSkillTaskService.class);
        when(taskService.submit(
                42L, "agent-1", "session-1", "technical-decision-comparison", "request-key-1",
                Map.of("question", "compare")
        )).thenReturn(new BuiltinSkillTaskService.SubmissionResult(
                "task-1", "PENDING", "/api/skill-tasks/task-1", false
        ));
        BuiltinSkillExecutionTask task = new BuiltinSkillExecutionTask();
        task.setTaskId("00000000-0000-0000-0000-000000000001");
        task.setStatus("COMPLETED");
        task.setProgress((short) 100);
        task.setResultJson("{\"conclusion\":\"ready\"}");
        task.setResultReference("/api/skill-tasks/" + task.getTaskId());
        when(taskService.getOwnedTask(task.getTaskId(), 42L)).thenReturn(task);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new BuiltinSkillTaskController(
                        requestScopeData, taskService
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/agents/agent-1/skills/technical-decision-comparison/tasks")
                        .contentType("application/json")
                        .header("Idempotency-Key", "request-key-1")
                        .content("{\"sessionId\":\"session-1\",\"input\":{\"question\":\"compare\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.replayed").value(false));
        mockMvc.perform(get("/api/skill-tasks/" + task.getTaskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progress").value(100))
                .andExpect(jsonPath("$.data.resultJson").value("{\"conclusion\":\"ready\"}"));

        verify(taskService).submit(
                42L, "agent-1", "session-1", "technical-decision-comparison", "request-key-1",
                Map.of("question", "compare")
        );
        verify(taskService).getOwnedTask(task.getTaskId(), 42L);
    }

    @Test
    void rejectsAnonymousTaskRequestsBeforeCallingTheService() throws Exception {
        BuiltinSkillTaskService taskService = mock(BuiltinSkillTaskService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new BuiltinSkillTaskController(
                        new RequestScopeData(), taskService
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/skill-tasks/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("用户未登录"));
        mockMvc.perform(post("/api/agents/agent-1/skills/technical-decision-comparison/tasks")
                        .contentType("application/json")
                        .header("Idempotency-Key", "request-key-1")
                        .content("{\"sessionId\":\"session-1\",\"input\":{\"question\":\"compare\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("用户未登录"));

        verifyNoInteractions(taskService);
    }
}
