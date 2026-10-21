package com.kama.jchatmind.controller;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.workflow.WorkflowExecutionService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflows")
@AllArgsConstructor
public class WorkflowController {

    private final RequestScopeData requestScopeData;
    private final WorkflowExecutionService workflowExecutionService;

    @PostMapping("/execute")
    public ApiResponse<WorkflowExecutionService.WorkflowExecutionResponse> execute(
            @RequestBody ExecuteWorkflowRequest request
    ) {
        WorkflowExecutionService.WorkflowExecutionCommand command =
                new WorkflowExecutionService.WorkflowExecutionCommand(
                        request.agentId(),
                        request.sessionId(),
                        request.request(),
                        request.sourceId()
                );
        return ApiResponse.success(workflowExecutionService.execute(requestScopeData.getUserId(), command));
    }

    public record ExecuteWorkflowRequest(String agentId, String sessionId, String request, String sourceId) {
    }
}
