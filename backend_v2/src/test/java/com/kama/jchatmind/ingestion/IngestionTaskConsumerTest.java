package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.service.impl.IngestionTaskServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionTaskConsumerTest {

    @Test
    void shouldCompleteClaimedTaskAfterProcessorSucceeds() {
        IngestionTaskServiceImpl service = mock(IngestionTaskServiceImpl.class);
        IngestionTask task = task();
        when(service.claimTask("task-1")).thenReturn(task);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ConsumerFixture fixture = consumer(service, rabbitTemplate, false);

        consume(fixture.consumer(), "task-1");

        assertThat(fixture.processedCount().get()).isEqualTo(1);
        verify(service).completeClaimedTask(task);
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRouteRetryingTaskToRetryQueueAfterProcessorFailure() {
        IngestionTaskServiceImpl service = mock(IngestionTaskServiceImpl.class);
        IngestionTask task = task();
        when(service.claimTask("task-1")).thenReturn(task);
        when(service.failClaimedTask(eq(task), anyString()))
                .thenReturn(IngestionTaskStateMachine.Status.RETRYING);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ConsumerFixture fixture = consumer(service, rabbitTemplate, true);

        consume(fixture.consumer(), "task-1");

        verify(rabbitTemplate).convertAndSend("ingestion.retry.exchange", "ingestion.retry", "task-1");
    }

    @Test
    void shouldRouteExhaustedTaskToDeadLetterQueueAfterProcessorFailure() {
        IngestionTaskServiceImpl service = mock(IngestionTaskServiceImpl.class);
        IngestionTask task = task();
        when(service.claimTask("task-1")).thenReturn(task);
        when(service.failClaimedTask(eq(task), anyString()))
                .thenReturn(IngestionTaskStateMachine.Status.DEAD_LETTER);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ConsumerFixture fixture = consumer(service, rabbitTemplate, true);

        consume(fixture.consumer(), "task-1");

        verify(rabbitTemplate).convertAndSend("ingestion.dlx", "ingestion.dlq", "task-1");
    }

    @Test
    void shouldIgnoreMessageWhenTaskCannotBeClaimed() {
        IngestionTaskServiceImpl service = mock(IngestionTaskServiceImpl.class);
        when(service.claimTask("task-1")).thenReturn(null);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ConsumerFixture fixture = consumer(service, rabbitTemplate, false);

        consume(fixture.consumer(), "task-1");

        assertThat(fixture.processedCount().get()).isZero();
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString());
    }

    private ConsumerFixture consumer(
            IngestionTaskServiceImpl service,
            RabbitTemplate rabbitTemplate,
            boolean shouldFail
    ) {
        try {
            Class<?> processorType = Class.forName("com.kama.jchatmind.ingestion.IngestionTaskProcessor");
            AtomicInteger processedCount = new AtomicInteger();
            Object processor = Proxy.newProxyInstance(
                    processorType.getClassLoader(),
                    new Class<?>[]{processorType},
                    (proxy, method, arguments) -> {
                        processedCount.incrementAndGet();
                        if (shouldFail) {
                            throw new IllegalStateException("parse failed");
                        }
                        return null;
                    }
            );
            Class<?> consumerType = Class.forName("com.kama.jchatmind.ingestion.IngestionTaskConsumer");
            Object consumer = consumerType.getConstructor(
                    IngestionTaskServiceImpl.class,
                    processorType,
                    RabbitTemplate.class
            ).newInstance(service, processor, rabbitTemplate);
            return new ConsumerFixture(consumer, processedCount);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 摄入任务消费者尚未实现", e);
        }
    }

    private void consume(Object consumer, String taskId) {
        try {
            Method method = consumer.getClass().getMethod("onMessage", String.class);
            method.invoke(consumer, taskId);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 消费者入口尚未实现", e);
        }
    }

    private IngestionTask task() {
        return IngestionTask.builder()
                .id("task-1")
                .status("RUNNING")
                .attemptCount(0)
                .maxAttempts(3)
                .build();
    }

    private record ConsumerFixture(Object consumer, AtomicInteger processedCount) {
    }
}
