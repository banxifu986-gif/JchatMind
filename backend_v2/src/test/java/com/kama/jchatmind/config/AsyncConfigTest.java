package com.kama.jchatmind.config;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.event.listener.AgentTaskExecutorMetrics;
import com.kama.jchatmind.event.listener.ChatEventListener;
import com.kama.jchatmind.event.listener.ChatSessionExecutionCoordinator;
import com.kama.jchatmind.event.listener.MemoryExtractionFailureRegistry;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncConfigTest {

    @Test
    void shouldDispatchChatEventsThroughDedicatedBoundedAgentExecutor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AsyncConfig.class)) {
            assertThat(context.containsBean("agentTaskExecutor")).isTrue();
            ThreadPoolTaskExecutor executor = context.getBean("agentTaskExecutor", ThreadPoolTaskExecutor.class);

            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadPoolExecutor().getQueue())
                    .isInstanceOf(BlockingQueue.class);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(50);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("agent-event-");

            var constructor = ChatEventListener.class.getDeclaredConstructors()[0];
            var executorParameter = constructor.getParameters()[constructor.getParameterCount() - 1];

            assertThat(executorParameter.getType()).isEqualTo(Executor.class);
            assertThat(executorParameter.getAnnotation(Qualifier.class).value()).isEqualTo("agentTaskExecutor");
        }
    }

    @Test
    void shouldExposeAgentTaskExecutorMetricsThroughLocalJmx() throws Exception {
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
        ObjectName objectName = new ObjectName("com.kama.jchatmind:type=AgentTaskExecutorMetrics");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AsyncConfig.class);
            context.registerBean(JChatMindFactory.class, () -> mock(JChatMindFactory.class));
            context.registerBean(UserMemoryFacadeService.class, () -> mock(UserMemoryFacadeService.class));
            context.registerBean(ChatSessionExecutionCoordinator.class);
            context.registerBean(MemoryExtractionFailureRegistry.class);
            context.registerBean(ChatEventListener.class);
            context.registerBean(AgentTaskExecutorMetrics.class);
            context.refresh();

            assertThat(mBeanServer.isRegistered(objectName)).isTrue();
            assertThat(mBeanServer.getAttribute(objectName, "RejectedTaskCount")).isEqualTo(0L);
            assertThat(mBeanServer.getAttribute(objectName, "PoolSize")).isEqualTo(0);
            assertThat(mBeanServer.getAttribute(objectName, "ActiveThreads")).isEqualTo(0);
            assertThat(mBeanServer.getAttribute(objectName, "QueuedTasks")).isEqualTo(0);
            assertThat(mBeanServer.getAttribute(objectName, "RemainingQueueCapacity")).isEqualTo(50);
        }

        assertThat(mBeanServer.isRegistered(objectName)).isFalse();
    }

    @Test
    void shouldRejectWhenAgentExecutorAndQueueAreFull() throws Exception {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest())
        );
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
        ObjectName objectName = new ObjectName("com.kama.jchatmind:type=AgentTaskExecutorMetrics");
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blockingTask = () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };

        try {
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.register(AsyncConfig.class);
                context.registerBean(JChatMindFactory.class, () -> mock(JChatMindFactory.class));
                context.registerBean(UserMemoryFacadeService.class, () -> mock(UserMemoryFacadeService.class));
                context.registerBean(ChatSessionExecutionCoordinator.class);
                context.registerBean(MemoryExtractionFailureRegistry.class);
                context.registerBean(ChatEventListener.class);
                context.registerBean(AgentTaskExecutorMetrics.class);
                context.refresh();

                try {
                    ThreadPoolTaskExecutor executor = context.getBean("agentTaskExecutor", ThreadPoolTaskExecutor.class);
                    boolean rejected = false;
                    for (int i = 0; i < 200 && !rejected; i++) {
                        try {
                            executor.execute(blockingTask);
                        } catch (TaskRejectedException exception) {
                            rejected = true;
                        }
                    }
                    assertThat(rejected).isTrue();
                    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(executor.getActiveCount()).isEqualTo(4);
                    assertThat(executor.getThreadPoolExecutor().getQueue().size()).isEqualTo(50);

                    ChatEventListener listener = context.getBean(ChatEventListener.class);
                    assertThatThrownBy(() -> listener.handle(new ChatEvent(
                            "7", "agent-1", "session-1", "队列已满"
                    ))).isInstanceOf(TaskRejectedException.class);

                    ChatEventListener.ExecutorSnapshot snapshot = listener.getExecutorSnapshot();
                    assertThat(snapshot.rejectedTaskCount()).isEqualTo(2);
                    assertThat(snapshot.poolSize()).isEqualTo(4);
                    assertThat(snapshot.activeThreads()).isEqualTo(4);
                    assertThat(snapshot.queuedTasks()).isEqualTo(50);
                    assertThat(snapshot.remainingQueueCapacity()).isZero();
                    assertThat(mBeanServer.getAttribute(objectName, "RejectedTaskCount")).isEqualTo(2L);
                    assertThat(mBeanServer.getAttribute(objectName, "PoolSize")).isEqualTo(4);
                    assertThat(mBeanServer.getAttribute(objectName, "ActiveThreads")).isEqualTo(4);
                    assertThat(mBeanServer.getAttribute(objectName, "QueuedTasks")).isEqualTo(50);
                    assertThat(mBeanServer.getAttribute(objectName, "RemainingQueueCapacity")).isEqualTo(0);

                    release.countDown();
                    CountDownLatch recoveryCompleted = new CountDownLatch(1);
                    executor.execute(recoveryCompleted::countDown);
                    assertThat(recoveryCompleted.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
                } finally {
                    release.countDown();
                }
            }
            assertThat(mBeanServer.isRegistered(objectName)).isFalse();
        } finally {
            release.countDown();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void shouldDrainBoundedBurstAndProcessNextEventAfterRecovery() throws Exception {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest())
        );
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
        ObjectName objectName = new ObjectName("com.kama.jchatmind:type=AgentTaskExecutorMetrics");
        CountDownLatch firstFourRunning = new CountDownLatch(4);
        CountDownLatch releaseFirstFour = new CountDownLatch(1);
        CountDownLatch initialEventsExtracted = new CountDownLatch(54);
        CountDownLatch rejectedSessionRecovered = new CountDownLatch(1);
        AtomicInteger agentRuns = new AtomicInteger();
        JChatMind agent = mock(JChatMind.class);
        doAnswer(invocation -> {
            if (agentRuns.incrementAndGet() <= 4) {
                firstFourRunning.countDown();
                releaseFirstFour.await();
            }
            return null;
        }).when(agent).run();
        JChatMindFactory factory = mock(JChatMindFactory.class);
        when(factory.create(anyString(), anyString(), anyString())).thenReturn(agent);
        UserMemoryFacadeService memoryService = mock(UserMemoryFacadeService.class);
        doAnswer(invocation -> {
            String sessionId = invocation.getArgument(1);
            if ("session-rejected".equals(sessionId)) {
                rejectedSessionRecovered.countDown();
            } else {
                initialEventsExtracted.countDown();
            }
            return com.kama.jchatmind.service.MemoryExtractionResult.SKIPPED;
        }).when(memoryService).extractMemoryCandidates(anyString(), anyString());

        try {
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.register(AsyncConfig.class);
                context.registerBean(JChatMindFactory.class, () -> factory);
                context.registerBean(UserMemoryFacadeService.class, () -> memoryService);
                context.registerBean(ChatSessionExecutionCoordinator.class);
                context.registerBean(MemoryExtractionFailureRegistry.class);
                context.registerBean(ChatEventListener.class);
                context.registerBean(AgentTaskExecutorMetrics.class);
                context.refresh();

                try {
                    ChatEventListener listener = context.getBean(ChatEventListener.class);
                    int accepted = 0;
                    boolean rejected = false;
                    for (int index = 0; index < 100 && !rejected; index++) {
                        String sessionId = index == 54 ? "session-rejected" : "session-" + index;
                        try {
                            listener.handle(new ChatEvent("7", "agent-1", sessionId, "负载测试"));
                            accepted++;
                        } catch (TaskRejectedException exception) {
                            rejected = true;
                        }
                    }

                    ChatEventListener.ExecutorSnapshot loadSnapshot = listener.getExecutorSnapshot();
                    assertThat(accepted)
                            .withFailMessage("accepted=%s, pool=%s, active=%s, queued=%s, remaining=%s",
                                    accepted,
                                    loadSnapshot.poolSize(),
                                    loadSnapshot.activeThreads(),
                                    loadSnapshot.queuedTasks(),
                                    loadSnapshot.remainingQueueCapacity())
                            .isEqualTo(54);
                    assertThat(rejected).isTrue();
                    assertThat(firstFourRunning.await(2, TimeUnit.SECONDS)).isTrue();
                    ChatEventListener.ExecutorSnapshot saturated = loadSnapshot;
                    assertThat(saturated.activeThreads()).isEqualTo(4);
                    assertThat(saturated.queuedTasks()).isEqualTo(50);
                    assertThat(saturated.remainingQueueCapacity()).isZero();
                    assertThat(saturated.rejectedTaskCount()).isEqualTo(2);

                    releaseFirstFour.countDown();
                    assertThat(initialEventsExtracted.await(5, TimeUnit.SECONDS)).isTrue();
                    listener.handle(new ChatEvent("7", "agent-1", "session-rejected", "恢复后重试"));
                    assertThat(rejectedSessionRecovered.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(listener.getExecutorSnapshot().queuedTasks()).isZero();
                    assertThat(mBeanServer.getAttribute(objectName, "RejectedTaskCount")).isEqualTo(2L);
                } finally {
                    releaseFirstFour.countDown();
                }
            }
            assertThat(mBeanServer.isRegistered(objectName)).isFalse();
        } finally {
            releaseFirstFour.countDown();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void shouldRecoverExecutorAcrossTwentySaturationCycles() throws Exception {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest())
        );
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AsyncConfig.class)) {
            ThreadPoolTaskExecutor executor = context.getBean("agentTaskExecutor", ThreadPoolTaskExecutor.class);
            for (int cycle = 0; cycle < 20; cycle++) {
                CountDownLatch started = new CountDownLatch(4);
                CountDownLatch firstTwoStarted = new CountDownLatch(2);
                CountDownLatch completed = new CountDownLatch(54);
                CountDownLatch release = new CountDownLatch(1);
                Runnable blockingTask = () -> {
                    started.countDown();
                    firstTwoStarted.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completed.countDown();
                    }
                };
                int accepted = 0;
                boolean rejected = false;

                try {
                    if (cycle == 0) {
                        executor.execute(blockingTask);
                        executor.execute(blockingTask);
                        assertThat(firstTwoStarted.await(2, TimeUnit.SECONDS)).isTrue();
                        accepted += 2;
                        for (int i = 0; i < 50; i++) {
                            executor.execute(completed::countDown);
                            accepted++;
                        }
                        executor.execute(blockingTask);
                        executor.execute(blockingTask);
                        accepted += 2;
                    } else {
                        assertThat(executor.getPoolSize()).isEqualTo(4);
                        for (int i = 0; i < 4; i++) {
                            executor.execute(blockingTask);
                            accepted++;
                        }
                        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                        for (int i = 0; i < 50; i++) {
                            executor.execute(completed::countDown);
                            accepted++;
                        }
                    }

                    try {
                        executor.execute(() -> {
                        });
                    } catch (TaskRejectedException exception) {
                        rejected = true;
                    }
                    assertThat(accepted)
                            .withFailMessage("cycle=%s, accepted=%s, pool=%s, active=%s, queued=%s",
                                    cycle,
                                    accepted,
                                    executor.getPoolSize(),
                                    executor.getActiveCount(),
                                    executor.getThreadPoolExecutor().getQueue().size())
                            .isEqualTo(54);
                    assertThat(rejected).isTrue();
                    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(executor.getActiveCount()).isEqualTo(4);
                    assertThat(executor.getThreadPoolExecutor().getQueue().size()).isEqualTo(50);

                    release.countDown();
                    assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();

                    CountDownLatch nextTaskCompleted = new CountDownLatch(1);
                    executor.execute(nextTaskCompleted::countDown);
                    assertThat(nextTaskCompleted.await(2, TimeUnit.SECONDS)).isTrue();
                } finally {
                    release.countDown();
                }
            }
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "g5.executor.soak", matches = "true")
    void shouldRemainBoundedDuringContinuousSubmissionSoak() throws Exception {
        int durationSeconds = Integer.getInteger("g5.executor.soak.durationSeconds", 30);
        assertThat(durationSeconds).isPositive();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest())
        );
        AtomicInteger acceptedTasks = new AtomicInteger();
        AtomicInteger rejectedTasks = new AtomicInteger();
        AtomicInteger completedTasks = new AtomicInteger();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AsyncConfig.class)) {
            ThreadPoolTaskExecutor executor = context.getBean("agentTaskExecutor", ThreadPoolTaskExecutor.class);
            Runnable task = () -> {
                try {
                    Thread.sleep(5);
                    completedTasks.incrementAndGet();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            };
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);
            int submissions = 0;
            while (System.nanoTime() < deadline) {
                try {
                    executor.execute(task);
                    acceptedTasks.incrementAndGet();
                } catch (TaskRejectedException exception) {
                    rejectedTasks.incrementAndGet();
                }
                if (++submissions % 128 == 0) {
                    assertThat(executor.getPoolSize()).isLessThanOrEqualTo(4);
                    assertThat(executor.getActiveCount()).isLessThanOrEqualTo(4);
                    assertThat(executor.getThreadPoolExecutor().getQueue().size()).isLessThanOrEqualTo(50);
                }
            }

            long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < drainDeadline
                    && (executor.getActiveCount() > 0 || !executor.getThreadPoolExecutor().getQueue().isEmpty())) {
                Thread.sleep(10);
            }

            assertThat(acceptedTasks.get()).isPositive();
            assertThat(rejectedTasks.get()).isPositive();
            assertThat(executor.getActiveCount()).isZero();
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
            assertThat(completedTasks.get()).isEqualTo(acceptedTasks.get());

            CountDownLatch recoveryTaskCompleted = new CountDownLatch(1);
            executor.execute(recoveryTaskCompleted::countDown);
            assertThat(recoveryTaskCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
