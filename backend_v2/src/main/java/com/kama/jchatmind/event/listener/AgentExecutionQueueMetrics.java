package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.service.AgentExecutionTaskQueue;
import lombok.AllArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
@ManagedResource(objectName = "com.kama.jchatmind:type=AgentExecutionQueueMetrics")
@ConditionalOnProperty(prefix = "agent.execution.queue", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentExecutionQueueMetrics {

    private final AgentExecutionTaskWorker worker;
    private final AgentExecutionTaskQueue queue;

    @ManagedAttribute
    public long getRejectedTaskCount() {
        return worker.getExecutorSnapshot().rejectedTaskCount();
    }

    @ManagedAttribute
    public int getPoolSize() {
        return worker.getExecutorSnapshot().poolSize();
    }

    @ManagedAttribute
    public int getActiveThreads() {
        return worker.getExecutorSnapshot().activeThreads();
    }

    @ManagedAttribute
    public int getQueuedTasks() {
        return worker.getExecutorSnapshot().queuedTasks();
    }

    @ManagedAttribute
    public int getRemainingQueueCapacity() {
        return worker.getExecutorSnapshot().remainingQueueCapacity();
    }

    @ManagedAttribute
    public long getPendingTaskCount() {
        return queue.countByStatus("PENDING");
    }

    @ManagedAttribute
    public long getRunningTaskCount() {
        return queue.countByStatus("RUNNING");
    }

    @ManagedAttribute
    public long getFailedTaskCount() {
        return queue.countByStatus("FAILED");
    }
}
