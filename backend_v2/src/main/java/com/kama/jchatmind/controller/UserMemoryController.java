package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.UpdateUserMemoryRequest;
import com.kama.jchatmind.model.request.UpdateUserMemoryExpirationRequest;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@AllArgsConstructor
public class UserMemoryController {

    private final UserMemoryFacadeService userMemoryFacadeService;

    @GetMapping("/memories")
    public ApiResponse<com.kama.jchatmind.model.response.GetUserMemoriesResponse> getUserMemories() {
        return ApiResponse.success(userMemoryFacadeService.getUserMemories());
    }

    @GetMapping("/memory-candidates")
    public ApiResponse<com.kama.jchatmind.model.response.GetUserMemoryCandidatesResponse> getUserMemoryCandidates() {
        return ApiResponse.success(userMemoryFacadeService.getUserMemoryCandidates());
    }

    @PostMapping("/memory-candidates/{candidateId}/confirm")
    public ApiResponse<Void> confirmUserMemoryCandidate(@PathVariable String candidateId) {
        userMemoryFacadeService.confirmUserMemoryCandidate(candidateId);
        return ApiResponse.success();
    }

    @PostMapping("/memory-candidates/{candidateId}/discard")
    public ApiResponse<Void> discardUserMemoryCandidate(@PathVariable String candidateId) {
        userMemoryFacadeService.discardUserMemoryCandidate(candidateId);
        return ApiResponse.success();
    }

    @DeleteMapping("/memories/{memoryId}")
    public ApiResponse<Void> deleteMemory(@PathVariable String memoryId) {
        userMemoryFacadeService.deleteMemory(memoryId);
        return ApiResponse.success();
    }

    @PatchMapping("/memories/{memoryId}")
    public ApiResponse<Void> updateMemory(
            @PathVariable String memoryId,
            @RequestBody UpdateUserMemoryRequest request
    ) {
        userMemoryFacadeService.updateMemory(memoryId, request.getContent());
        return ApiResponse.success();
    }

    @PatchMapping("/memories/{memoryId}/expiration")
    public ApiResponse<Void> updateMemoryExpiration(
            @PathVariable String memoryId,
            @RequestBody UpdateUserMemoryExpirationRequest request
    ) {
        userMemoryFacadeService.updateMemoryExpiration(memoryId, request.getExpiresAt());
        return ApiResponse.success();
    }

    @DeleteMapping("/memories")
    public ApiResponse<Void> clearUserMemories() {
        userMemoryFacadeService.clearUserMemories();
        return ApiResponse.success();
    }
}
