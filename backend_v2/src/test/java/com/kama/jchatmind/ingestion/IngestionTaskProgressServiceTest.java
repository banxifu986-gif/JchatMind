package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.entity.IngestionTask;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionTaskProgressServiceTest {

    @Test
    void shouldConnectAndPublishLatestTaskProgressWithoutExternalBroker() {
        IngestionTaskProgressServiceImpl service = new IngestionTaskProgressServiceImpl(new ObjectMapper());
        IngestionTask queued = IngestionTask.builder()
                .id("task-1")
                .kbId("kb-1")
                .documentId("doc-1")
                .status("QUEUED")
                .attemptCount(0)
                .maxAttempts(3)
                .build();

        service.publish(queued);
        SseEmitter emitter = service.connect(queued);
        service.publish(IngestionTask.builder()
                .id("task-1")
                .kbId("kb-1")
                .documentId("doc-1")
                .status("RUNNING")
                .attemptCount(0)
                .maxAttempts(3)
                .build());

        assertThat(emitter).isNotNull();
        assertThat(service.latest("task-1")).isPresent();
        assertThat(service.latest("task-1").orElseThrow().status()).isEqualTo("RUNNING");
        assertThat(service.latest("task-1").orElseThrow().sequence()).isEqualTo(2);
    }

    @Test
    void shouldPurgeExpiredTerminalProgressFromSingleInstanceMemory() throws Exception {
        IngestionTaskProgressServiceImpl service = new IngestionTaskProgressServiceImpl(new ObjectMapper());
        IngestionTask terminal = task("task-terminal", "DEAD_LETTER");

        service.publish(terminal);
        assertThat(service.latest("task-terminal")).isPresent()
                .get().extracting(IngestionTaskProgressEvent::status).isEqualTo("DEAD_LETTER");
        markTerminalEventExpired(service, "task-terminal");

        service.publish(task("task-active", "QUEUED"));

        assertThat(service.latest("task-terminal")).isEmpty();
        assertThat(internalMap(service, "eventHistory")).doesNotContainKey("task-terminal");
        assertThat(internalMap(service, "sequenceCounters")).doesNotContainKey("task-terminal");
        assertThat(internalMap(service, "taskLocks")).doesNotContainKey("task-terminal");
    }

    private IngestionTask task(String taskId, String status) {
        return IngestionTask.builder()
                .id(taskId)
                .kbId("kb-1")
                .documentId("doc-1")
                .status(status)
                .attemptCount("DEAD_LETTER".equals(status) ? 3 : 0)
                .maxAttempts(3)
                .build();
    }

    @SuppressWarnings("unchecked")
    private void markTerminalEventExpired(IngestionTaskProgressServiceImpl service, String taskId) throws Exception {
        internalMap(service, "latestEventTimes").put(taskId, System.currentTimeMillis() - 30 * 60 * 1000L - 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> internalMap(IngestionTaskProgressServiceImpl service, String fieldName) throws Exception {
        Field field = IngestionTaskProgressServiceImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, Object>) field.get(service);
    }
}
