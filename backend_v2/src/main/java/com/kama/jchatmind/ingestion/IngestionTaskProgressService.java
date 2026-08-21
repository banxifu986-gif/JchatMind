package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.model.entity.IngestionTask;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface IngestionTaskProgressService {
    SseEmitter connect(IngestionTask task);

    default SseEmitter connect(IngestionTask task, Long lastEventId) {
        return connect(task);
    }

    void publish(IngestionTask task);
}
