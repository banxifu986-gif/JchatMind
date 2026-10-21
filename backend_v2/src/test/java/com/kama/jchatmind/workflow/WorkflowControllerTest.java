package com.kama.jchatmind.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.controller.WorkflowController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowControllerTest {

    @Test
    void shouldExecuteWorkflowForAuthenticatedOwner() throws Exception {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);
        WorkflowExecutionService service = mock(WorkflowExecutionService.class);
        when(service.execute(eq(7L), eq(new WorkflowExecutionService.WorkflowExecutionCommand(
                "agent-1", "session-1", "question", "build-system"))))
                .thenReturn(new WorkflowExecutionService.WorkflowExecutionResponse(
                        "trace-1",
                        "WORKFLOW",
                        WorkflowExecutionResult.completed(java.util.List.of(new WorkflowStepExecution(
                                "step-1", WorkflowStepExecutionStatus.SUCCEEDED, "evidence"
                        ))),
                        null,
                        java.util.List.of()
                ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new WorkflowController(requestScopeData, service)).build();

        mockMvc.perform(post("/api/workflows/execute")
                        .contentType("application/json")
                        .content(new ObjectMapper().writeValueAsBytes(new ExecuteWorkflowRequest(
                                "agent-1", "session-1", "question", "build-system"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.traceId").value("trace-1"))
                .andExpect(jsonPath("$.data.workflowExecution.steps[0].status").value("SUCCEEDED"));

        verify(service).execute(eq(7L), eq(new WorkflowExecutionService.WorkflowExecutionCommand(
                "agent-1", "session-1", "question", "build-system")));
    }

    public record ExecuteWorkflowRequest(String agentId, String sessionId, String request, String sourceId) {
    }
}
