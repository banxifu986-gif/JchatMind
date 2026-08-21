package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.model.vo.IngestionTaskVO;
import com.kama.jchatmind.service.impl.IngestionTaskServiceImpl;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingestion/tasks")
@AllArgsConstructor
public class IngestionTaskController {

    private final IngestionTaskServiceImpl ingestionTaskService;

    @GetMapping("/{taskId}")
    public ApiResponse<IngestionTaskVO> getTask(@PathVariable String taskId) {
        return ApiResponse.success(toVO(ingestionTaskService.getTask(taskId)));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<Void> cancelTask(@PathVariable String taskId) {
        ingestionTaskService.cancelTask(taskId);
        return ApiResponse.success();
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<Void> retryTask(@PathVariable String taskId) {
        ingestionTaskService.retryTask(taskId);
        return ApiResponse.success();
    }

    private IngestionTaskVO toVO(IngestionTask task) {
        return IngestionTaskVO.builder()
                .taskId(task.getId())
                .kbId(task.getKbId())
                .documentId(task.getDocumentId())
                .taskType(task.getTaskType())
                .status(task.getStatus())
                .attemptCount(task.getAttemptCount())
                .maxAttempts(task.getMaxAttempts())
                .errorSummary(task.getErrorSummary())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .build();
    }
}
