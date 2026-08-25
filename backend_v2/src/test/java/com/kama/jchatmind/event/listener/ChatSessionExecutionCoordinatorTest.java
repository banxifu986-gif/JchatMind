package com.kama.jchatmind.event.listener;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatSessionExecutionCoordinatorTest {

    @Test
    void shouldNotRunSameSessionTasksConcurrently() throws Exception {
        ChatSessionExecutionCoordinator coordinator = new ChatSessionExecutionCoordinator();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        try {
            Future<?> first = executor.submit(() -> coordinator.execute("session-1", () -> {
                executionOrder.add("first-start");
                firstStarted.countDown();
                await(releaseFirst);
                executionOrder.add("first-finish");
            }));
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> coordinator.execute("session-1", () -> {
                executionOrder.add("second");
                secondStarted.countDown();
            }));

            assertThat(secondStarted.await(300, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirst.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);

            assertThat(executionOrder).containsExactly("first-start", "first-finish", "second");
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldAllowDifferentSessionTasksToRunConcurrently() throws Exception {
        ChatSessionExecutionCoordinator coordinator = new ChatSessionExecutionCoordinator();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        try {
            Future<?> first = executor.submit(() -> coordinator.execute("session-1", () -> {
                firstStarted.countDown();
                await(releaseFirst);
            }));
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> coordinator.execute("session-2", secondFinished::countDown));

            assertThat(secondFinished.await(1, TimeUnit.SECONDS)).isTrue();

            releaseFirst.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReleaseSameSessionAfterTaskFails() {
        ChatSessionExecutionCoordinator coordinator = new ChatSessionExecutionCoordinator();
        AtomicBoolean secondTaskRan = new AtomicBoolean();

        assertThatThrownBy(() -> coordinator.execute("session-1", () -> {
            throw new IllegalStateException("agent unavailable");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("agent unavailable");

        coordinator.execute("session-1", () -> secondTaskRan.set(true));

        assertThat(secondTaskRan).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
