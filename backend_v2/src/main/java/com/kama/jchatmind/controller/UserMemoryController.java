package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.response.GetUserMemoriesResponse;
import com.kama.jchatmind.model.response.GetUserMemoryCandidatesResponse;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/{userId}")
@AllArgsConstructor
public class UserMemoryController {

    private final UserMemoryFacadeService userMemoryFacadeService;

    @GetMapping("/memories")
    public ApiResponse<GetUserMemoriesResponse> getUserMemories(@PathVariable String userId) {
        return ApiResponse.success(userMemoryFacadeService.getUserMemories(userId));
    }

    @GetMapping("/memory-candidates")
    public ApiResponse<GetUserMemoryCandidatesResponse> getUserMemoryCandidates(@PathVariable String userId) {
        return ApiResponse.success(userMemoryFacadeService.getUserMemoryCandidates(userId));
    }

    @PostMapping("/memory-candidates/{candidateId}/confirm")
    public ApiResponse<Void> confirmCandidate(@PathVariable String userId, @PathVariable String candidateId) {
        userMemoryFacadeService.confirmCandidate(userId, candidateId);
        return ApiResponse.success();
    }

    @DeleteMapping("/memories/{memoryId}")
    public ApiResponse<Void> deleteMemory(@PathVariable String userId, @PathVariable String memoryId) {
        userMemoryFacadeService.deleteMemory(userId, memoryId);
        return ApiResponse.success();
    }
}
