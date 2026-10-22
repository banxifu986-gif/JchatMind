package com.kama.jchatmind.controller;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.entity.BuiltinSkillExecutionTask;
import com.kama.jchatmind.skill.BuiltinSkillTaskService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@AllArgsConstructor
public class BuiltinSkillTaskController {

    private final RequestScopeData requestScopeData;
    private final BuiltinSkillTaskService taskService;

    @PostMapping("/api/agents/{agentId}/skills/{skillId}/tasks")
    public ApiResponse<BuiltinSkillTaskService.SubmissionResult> submit(
            @PathVariable String agentId,
            @PathVariable String skillId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SubmitSkillTaskRequest request
    ) {
        Long userId = requireUserId();
        if (request == null) {
            throw new BizException("内置 Skill 请求无效");
        }
        return ApiResponse.success(taskService.submit(
                userId, agentId, request.sessionId(), skillId, idempotencyKey, request.input()
        ));
    }

    @GetMapping("/api/skill-tasks/{taskId}")
    public ApiResponse<TaskStatusResponse> getStatus(@PathVariable String taskId) {
        BuiltinSkillExecutionTask task = taskService.getOwnedTask(taskId, requireUserId());
        return ApiResponse.success(new TaskStatusResponse(
                task.getTaskId(), task.getStatus(), task.getProgress(), task.getErrorSummary(),
                task.getFailureType(), task.getResultJson(), task.getResultReference()
        ));
    }

    private Long requireUserId() {
        Long userId = requestScopeData.getUserId();
        if (userId == null || userId <= 0) {
            throw new BizException("用户未登录");
        }
        return userId;
    }

    public record SubmitSkillTaskRequest(String sessionId, Map<String, Object> input) {
    }

    public record TaskStatusResponse(
            String taskId,
            String status,
            short progress,
            String errorSummary,
            String failureType,
            String resultJson,
            String resultReference
    ) {
    }
}
