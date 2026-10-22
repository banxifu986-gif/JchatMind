package com.kama.jchatmind.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.GlobalExceptionHandler;
import com.kama.jchatmind.skill.BuiltinSkillExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BuiltinSkillControllerHttpTest {

    @Test
    void bindsTheSkillRouteAndDelegatesAuthenticatedRequestJson() throws Exception {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(42L);
        BuiltinSkillExecutionService executionService = mock(BuiltinSkillExecutionService.class);
        Map<String, Object> input = Map.of("question", "比较缓存方案", "kbIds", List.of("kb-2"));
        BuiltinSkillExecutionService.ExecutionResponse result =
                new BuiltinSkillExecutionService.ExecutionResponse(
                        Map.of("abstained", true, "reason", "no evidence"), true, List.of()
                );
        when(executionService.execute(
                42L, "agent-1", "session-1", "technical-decision-comparison", input
        )).thenReturn(result);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                        new BuiltinSkillController(requestScopeData, executionService)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/agents/agent-1/skills/technical-decision-comparison/execute")
                        .contentType("application/json")
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "sessionId", "session-1",
                                "input", input
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.output.abstained").value(true));

        verify(executionService).execute(
                42L, "agent-1", "session-1", "technical-decision-comparison", input
        );
    }

    @Test
    void returnsBusinessErrorForAnonymousHttpRequestWithoutDelegating() throws Exception {
        BuiltinSkillExecutionService executionService = mock(BuiltinSkillExecutionService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                        new BuiltinSkillController(new RequestScopeData(), executionService)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/agents/agent-1/skills/technical-decision-comparison/execute")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"session-1\",\"input\":{\"question\":\"比较方案\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("用户未登录"));

        verifyNoInteractions(executionService);
    }
}
