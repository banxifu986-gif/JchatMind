package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.CreateKnowledgeBaseRequest;
import com.kama.jchatmind.model.request.UpdateKnowledgeBaseRequest;
import com.kama.jchatmind.model.response.CreateKnowledgeBaseResponse;
import com.kama.jchatmind.model.response.DeleteKnowledgeBaseResponse;
import com.kama.jchatmind.model.response.GetKnowledgeBaseDeletionTaskResponse;
import com.kama.jchatmind.model.response.GetKnowledgeBasesResponse;
import com.kama.jchatmind.service.KnowledgeBaseFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseFacadeService knowledgeBaseFacadeService;

    // 查询所有知识库
    @GetMapping("/knowledge-bases")
    public ApiResponse<GetKnowledgeBasesResponse> getKnowledgeBases() {
        return ApiResponse.success(knowledgeBaseFacadeService.getKnowledgeBases());
    }

    // 创建知识库
    @PostMapping("/knowledge-bases")
    public ApiResponse<CreateKnowledgeBaseResponse> createKnowledgeBase(@RequestBody CreateKnowledgeBaseRequest request) {
        return ApiResponse.success(knowledgeBaseFacadeService.createKnowledgeBase(request));
    }

    // 删除知识库
    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}")
    public ApiResponse<DeleteKnowledgeBaseResponse> deleteKnowledgeBase(@PathVariable String knowledgeBaseId) {
        return ApiResponse.success(knowledgeBaseFacadeService.deleteKnowledgeBase(knowledgeBaseId));
    }

    @GetMapping("/knowledge-base-deletion-tasks/{taskId}")
    public ApiResponse<GetKnowledgeBaseDeletionTaskResponse> getKnowledgeBaseDeletionTask(@PathVariable String taskId) {
        return ApiResponse.success(knowledgeBaseFacadeService.getKnowledgeBaseDeletionTask(taskId));
    }

    // 更新知识库
    @PatchMapping("/knowledge-bases/{knowledgeBaseId}")
    public ApiResponse<Void> updateKnowledgeBase(@PathVariable String knowledgeBaseId, @RequestBody UpdateKnowledgeBaseRequest request) {
        knowledgeBaseFacadeService.updateKnowledgeBase(knowledgeBaseId, request);
        return ApiResponse.success();
    }
}
