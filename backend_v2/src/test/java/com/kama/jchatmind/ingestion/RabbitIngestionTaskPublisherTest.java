package com.kama.jchatmind.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RabbitIngestionTaskPublisherTest {

    @Test
    void shouldPublishOnlyTaskIdToDedicatedIngestionRoute() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        Object publisher = publisher(rabbitTemplate);

        publish(publisher, "task-1");

        verify(rabbitTemplate).convertAndSend("ingestion.exchange", "ingestion.submit", "task-1");
    }

    @Test
    void shouldDeclareDedicatedIngestionRetryAndDeadLetterRoutes() throws Exception {
        String config = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src", "main", "java", "com", "kama", "jchatmind", "config", "RabbitMQConfig.java"
        ));

        assertThat(config)
                .contains("INGESTION_EXCHANGE")
                .contains("INGESTION_RETRY_EXCHANGE")
                .contains("INGESTION_DLQ")
                .contains("ingestionQueue")
                .contains("ingestionRetryQueue")
                .contains("ingestionDlq");
    }

    private Object publisher(RabbitTemplate rabbitTemplate) {
        try {
            Class<?> publisherType = Class.forName("com.kama.jchatmind.ingestion.RabbitIngestionTaskPublisher");
            return publisherType.getConstructor(RabbitTemplate.class).newInstance(rabbitTemplate);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 RabbitMQ 任务发布器尚未实现", e);
        }
    }

    private void publish(Object publisher, String taskId) {
        try {
            Method publish = publisher.getClass().getMethod("publish", String.class);
            publish.invoke(publisher, taskId);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 任务发布入口尚未实现", e);
        }
    }
}
