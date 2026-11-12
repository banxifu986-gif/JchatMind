package com.kama.jchatmind.controller;

import com.kama.jchatmind.auth.annotation.NeedLogin;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.UpdateUserMemoryRequest;
import com.kama.jchatmind.model.request.UpdateUserMemoryExpirationRequest;
import com.kama.jchatmind.model.request.UpdateUserMemoryAutomationRequest;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@AllArgsConstructor
public class UserMemoryController {

    private final UserMemoryFacadeService userMemoryFacadeService;

    @GetMapping("/memories")
    @NeedLogin
    public ApiResponse<com.kama.jchatmind.model.response.GetUserMemoriesResponse> getUserMemories() {
        return ApiResponse.success(userMemoryFacadeService.getUserMemories());
    }

    @GetMapping("/memory-candidates")
    @NeedLogin
    public ApiResponse<com.kama.jchatmind.model.response.GetUserMemoryCandidatesResponse> getUserMemoryCandidates() {
        return ApiResponse.success(userMemoryFacadeService.getUserMemoryCandidates());
    }

    @GetMapping("/memory-automation")
    @NeedLogin
    public ApiResponse<com.kama.jchatmind.model.response.UserMemoryAutomationResponse> getMemoryAutomationMode() {
        return ApiResponse.success(userMemoryFacadeService.getMemoryAutomationMode());
    }

    @PatchMapping("/memory-automation")
    @NeedLogin
    public ApiResponse<Void> updateMemoryAutomationMode(@RequestBody UpdateUserMemoryAutomationRequest request) {
        userMemoryFacadeService.updateMemoryAutomationMode(request.getMode());
        return ApiResponse.success();
    }

    @GetMapping("/memory-consolidation-proposals")
    @NeedLogin
    public ApiResponse<com.kama.jchatmind.model.response.GetUserMemoryConsolidationProposalsResponse> getMemoryConsolidationProposals() {
        return ApiResponse.success(userMemoryFacadeService.getMemoryConsolidationProposals());
    }

    @PostMapping("/memory-consolidation-proposals")
    @NeedLogin
    public ApiResponse<Void> createMemoryConsolidationProposal() {
        userMemoryFacadeService.createMemoryConsolidationProposal();
        return ApiResponse.success();
    }

    @PostMapping("/memory-consolidation-proposals/{proposalId}/confirm")
    @NeedLogin
    public ApiResponse<Void> confirmMemoryConsolidationProposal(@PathVariable String proposalId) {
        userMemoryFacadeService.confirmMemoryConsolidationProposal(proposalId);
        return ApiResponse.success();
    }

    @PostMapping("/memory-consolidation-proposals/{proposalId}/discard")
    @NeedLogin
    public ApiResponse<Void> discardMemoryConsolidationProposal(@PathVariable String proposalId) {
        userMemoryFacadeService.discardMemoryConsolidationProposal(proposalId);
        return ApiResponse.success();
    }

    @PostMapping("/memory-candidates/{candidateId}/confirm")
    @NeedLogin
    public ApiResponse<Void> confirmUserMemoryCandidate(@PathVariable String candidateId) {
        userMemoryFacadeService.confirmUserMemoryCandidate(candidateId);
        return ApiResponse.success();
    }

    @PostMapping("/memory-candidates/{candidateId}/discard")
    @NeedLogin
    public ApiResponse<Void> discardUserMemoryCandidate(@PathVariable String candidateId) {
        userMemoryFacadeService.discardUserMemoryCandidate(candidateId);
        return ApiResponse.success();
    }

    @DeleteMapping("/memories/{memoryId}")
    @NeedLogin
    public ApiResponse<Void> deleteMemory(@PathVariable String memoryId) {
        userMemoryFacadeService.deleteMemory(memoryId);
        return ApiResponse.success();
    }

    @PatchMapping("/memories/{memoryId}")
    @NeedLogin
    public ApiResponse<Void> updateMemory(
            @PathVariable String memoryId,
            @RequestBody UpdateUserMemoryRequest request
    ) {
        userMemoryFacadeService.updateMemory(memoryId, request.getContent());
        return ApiResponse.success();
    }

    @PatchMapping("/memories/{memoryId}/expiration")
    @NeedLogin
    public ApiResponse<Void> updateMemoryExpiration(
            @PathVariable String memoryId,
            @RequestBody UpdateUserMemoryExpirationRequest request
    ) {
        userMemoryFacadeService.updateMemoryExpiration(memoryId, request.getExpiresAt());
        return ApiResponse.success();
    }

    @DeleteMapping("/memories")
    @NeedLogin
    public ApiResponse<Void> clearUserMemories() {
        userMemoryFacadeService.clearUserMemories();
        return ApiResponse.success();
    }
}
