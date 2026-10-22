package com.kama.jchatmind;

import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

public final class G5FullApplicationQueueWorkerProcessProbe {

    private G5FullApplicationQueueWorkerProcessProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Worker id and ready/stop marker paths are required");
        }
        String workerId = args[0];
        Path readyFile = Path.of(args[1]);
        Path stopFile = Path.of(args[2]);
        try (ConfigurableApplicationContext context =
                     G5FullApplicationHttpPreflightL2Test.startSecondFullApplication(
                             "agent.execution.queue.enabled=true",
                             "g5.chat.full.queue.probe=true",
                             "g5.chat.full.queue.probe.worker-id=" + workerId
                     )) {
            if (context.getBeansOfType(
                    com.kama.jchatmind.event.listener.AgentExecutionTaskWorker.class).size() != 1) {
                throw new AssertionError("Full application process did not assemble its durable queue worker");
            }
            Files.writeString(readyFile, Long.toString(ProcessHandle.current().pid()));
            System.out.println("G5_FULL_QUEUE_WORKER_READY:" + workerId + ":" + ProcessHandle.current().pid());
            while (!Files.exists(stopFile)) {
                Thread.sleep(50);
            }
            System.out.println("G5_FULL_QUEUE_WORKER_STOP:" + workerId);
        }
    }
}
