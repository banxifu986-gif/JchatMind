package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
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

    @DeleteMapping("/memories/{memoryId}")
    public ApiResponse<Void> deleteMemory(@PathVariable String memoryId) {
        userMemoryFacadeService.deleteMemory(memoryId);
        return ApiResponse.success();
    }
}
