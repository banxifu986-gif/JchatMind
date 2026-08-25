package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.service.impl.IngestionTaskServiceImpl;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class IngestionTaskConsumer {

    private final IngestionTaskServiceImpl ingestionTaskService;
    private final IngestionTaskProcessor ingestionTaskProcessor;
    private final RabbitTemplate rabbitTemplate;
    private final IngestionTaskProgressService progressService;

    public IngestionTaskConsumer(
            IngestionTaskServiceImpl ingestionTaskService,
            IngestionTaskProcessor ingestionTaskProcessor,
            RabbitTemplate rabbitTemplate
    ) {
        this(ingestionTaskService, ingestionTaskProcessor, rabbitTemplate, new IngestionTaskProgressService() {
            @Override
            public org.springframework.web.servlet.mvc.method.annotation.SseEmitter connect(IngestionTask task) {
                return new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
            }

            @Override
            public void publish(IngestionTask task) {
            }
        });
    }

    @Autowired
    public IngestionTaskConsumer(
            IngestionTaskServiceImpl ingestionTaskService,
            IngestionTaskProcessor ingestionTaskProcessor,
            RabbitTemplate rabbitTemplate,
            IngestionTaskProgressService progressService
    ) {
        this.ingestionTaskService = ingestionTaskService;
        this.ingestionTaskProcessor = ingestionTaskProcessor;
        this.rabbitTemplate = rabbitTemplate;
        this.progressService = progressService;
    }

    @RabbitListener(
            queues = RabbitMQConfig.INGESTION_QUEUE,
            containerFactory = "ingestionRabbitListenerContainerFactory"
    )
    public void onMessage(String taskId) {
        taskId = normalizeTaskId(taskId);
        IngestionTask task = ingestionTaskService.claimTask(taskId);
        if (task == null) {
            return;
        }
        progressService.publish(task);
        try {
            ingestionTaskProcessor.process(task);
            ingestionTaskService.completeClaimedTask(task);
            progressService.publish(task);
        } catch (Exception e) {
            IngestionTaskStateMachine.Status nextStatus = ingestionTaskService.failClaimedTask(
                    task,
                    e.getClass().getSimpleName()
            );
            if (nextStatus == IngestionTaskStateMachine.Status.RETRYING) {
                progressService.publish(task);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.INGESTION_RETRY_EXCHANGE,
                        RabbitMQConfig.INGESTION_RETRY_ROUTING_KEY,
                        taskId
                );
            } else if (nextStatus == IngestionTaskStateMachine.Status.DEAD_LETTER) {
                progressService.publish(task);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.INGESTION_DLX,
                        RabbitMQConfig.INGESTION_DLQ_ROUTING_KEY,
                        taskId
                );
            }
        }
    }

    private String normalizeTaskId(String taskId) {
        if (taskId != null && taskId.length() >= 2
                && taskId.startsWith("\"") && taskId.endsWith("\"")) {
            return taskId.substring(1, taskId.length() - 1);
        }
        return taskId;
    }
}
