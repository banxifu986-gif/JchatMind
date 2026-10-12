package com.kama.jchatmind.event.listener;

import lombok.AllArgsConstructor;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
@ManagedResource(objectName = "com.kama.jchatmind:type=AgentTaskExecutorMetrics")
public class AgentTaskExecutorMetrics {

    private final ChatEventListener chatEventListener;

    @ManagedAttribute
    public long getRejectedTaskCount() {
        return chatEventListener.getRejectedTaskCount();
    }

    @ManagedAttribute
    public int getPoolSize() {
        return chatEventListener.getExecutorSnapshot().poolSize();
    }

    @ManagedAttribute
    public int getActiveThreads() {
        return chatEventListener.getExecutorSnapshot().activeThreads();
    }

    @ManagedAttribute
    public int getQueuedTasks() {
        return chatEventListener.getExecutorSnapshot().queuedTasks();
    }

    @ManagedAttribute
    public int getRemainingQueueCapacity() {
        return chatEventListener.getExecutorSnapshot().remainingQueueCapacity();
    }
}
