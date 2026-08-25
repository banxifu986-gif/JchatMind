package com.kama.jchatmind.deletion;

import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.model.entity.KnowledgeBaseDeletionTask;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.impl.KnowledgeBaseDeletionTaskServiceImpl;
import lombok.AllArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class KnowledgeBaseDeletionTaskConsumer {

    private final KnowledgeBaseDeletionTaskServiceImpl deletionTaskService;
    private final DocumentStorageService documentStorageService;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(
            queues = RabbitMQConfig.KNOWLEDGE_BASE_DELETION_QUEUE,
            containerFactory = "knowledgeBaseDeletionRabbitListenerContainerFactory"
    )
    public void onMessage(String taskId) {
        taskId = normalizeTaskId(taskId);
        KnowledgeBaseDeletionTask task = deletionTaskService.claimTask(taskId);
        if (task == null) {
            return;
        }
        try {
            documentStorageService.deleteKnowledgeBaseDirectory(task.getKnowledgeBaseId());
            deletionTaskService.completeClaimedTask(task);
        } catch (Exception e) {
            String nextStatus = deletionTaskService.failClaimedTask(task, e.getClass().getSimpleName());
            if ("RETRYING".equals(nextStatus)) {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.KNOWLEDGE_BASE_DELETION_RETRY_EXCHANGE,
                        RabbitMQConfig.KNOWLEDGE_BASE_DELETION_RETRY_ROUTING_KEY,
                        taskId
                );
            } else if ("DEAD_LETTER".equals(nextStatus)) {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.KNOWLEDGE_BASE_DELETION_DLX,
                        RabbitMQConfig.KNOWLEDGE_BASE_DELETION_DLQ_ROUTING_KEY,
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
