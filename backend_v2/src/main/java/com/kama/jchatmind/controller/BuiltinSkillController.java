package com.kama.jchatmind.controller;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.skill.BuiltinSkillExecutionService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/agents/{agentId}/skills")
@AllArgsConstructor
public class BuiltinSkillController {

    private final RequestScopeData requestScopeData;
    private final BuiltinSkillExecutionService executionService;

    @PostMapping("/{skillId}/execute")
    public ApiResponse<BuiltinSkillExecutionService.ExecutionResponse> execute(
            @PathVariable String agentId,
            @PathVariable String skillId,
            @RequestBody ExecuteSkillRequest request
    ) {
        Long userId = requestScopeData.getUserId();
        if (userId == null || userId <= 0) {
            throw new BizException("用户未登录");
        }
        if (request == null) {
            throw new BizException("内置 Skill 请求无效");
        }
        return ApiResponse.success(executionService.execute(
                userId, agentId, request.sessionId(), skillId, request.input()
        ));
    }

    public record ExecuteSkillRequest(String sessionId, Map<String, Object> input) {
    }
}
