package com.kama.jchatmind.controller;

import com.kama.jchatmind.ingestion.IngestionTaskProgressService;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.service.impl.IngestionTaskServiceImpl;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/sse/ingestion")
@AllArgsConstructor
public class IngestionTaskSseController {

    private final IngestionTaskServiceImpl ingestionTaskService;
    private final IngestionTaskProgressService progressService;

    @GetMapping(value = "/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(
            @PathVariable String taskId,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId
    ) {
        IngestionTask task = ingestionTaskService.getTask(taskId);
        return progressService.connect(task, lastEventId);
    }
}
