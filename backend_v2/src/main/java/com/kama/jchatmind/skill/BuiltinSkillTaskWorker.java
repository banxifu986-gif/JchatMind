package com.kama.jchatmind.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.BuiltinSkillTaskMapper;
import com.kama.jchatmind.model.entity.BuiltinSkillExecutionTask;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
@AllArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "builtin-skill.task.dispatch", name = "enabled", havingValue = "true")
public class BuiltinSkillTaskWorker {

    private static final int EXECUTION_LEASE_SECONDS = 300;
    private static final int RETRY_DELAY_SECONDS = 30;

    private final BuiltinSkillTaskMapper taskMapper;
    private final BuiltinSkillExecutionService executionService;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @RabbitListener(
            queues = RabbitMQConfig.BUILTIN_SKILL_TASK_QUEUE,
            containerFactory = "builtinSkillTaskRabbitListenerContainerFactory"
    )
    public void onMessage(String rawTaskId) {
        String taskId = normalizeTaskId(rawTaskId);
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        if (taskMapper.claimExecutionTask(taskId, EXECUTION_LEASE_SECONDS) != 1) {
            publishDeadLetterIfNeeded(taskId);
            return;
        }

        BuiltinSkillExecutionTask task = taskMapper.selectByTaskId(taskId);
        if (task == null) {
            return;
        }

        try {
            Map<String, Object> input = objectMapper.readValue(
                    task.getInputSnapshot(), new TypeReference<>() {
                    }
            );
            BuiltinSkillExecutionService.PreparedSkillExecution prepared = executionService.prepare(
                    task.getRequesterUserId(), task.getAgentId(), task.getSessionId(), task.getSkillId(), input
            );
            if (!task.getSkillVersion().equals(prepared.invocation().definition().version())) {
                throw new BizException("Skill 版本已变更，任务无法执行");
            }

            BuiltinSkillExecutionService.ExecutionResponse response = executionService.execute(prepared);
            if (!response.valid()) {
                failPermanently(task, "SkillOutputValidationFailure", "Skill 输出未通过校验");
                return;
            }
            String resultJson = objectMapper.writeValueAsString(response.output());
            taskMapper.completeExecutionTask(taskId, task.getAttemptCount(), resultJson);
        } catch (BizException | IllegalArgumentException | JsonProcessingException exception) {
            failPermanently(task, exception.getClass().getSimpleName(), "任务输入、授权或 Skill 版本校验失败");
        } catch (Exception exception) {
            retryOrDeadLetter(task, exception.getClass().getSimpleName());
        }
    }

    private void failPermanently(BuiltinSkillExecutionTask task, String failureType, String errorSummary) {
        taskMapper.failExecutionTask(task.getTaskId(), task.getAttemptCount(), failureType, errorSummary);
    }

    private void retryOrDeadLetter(BuiltinSkillExecutionTask task, String failureType) {
        if (task.getAttemptCount() < task.getMaxAttempts()) {
            if (taskMapper.retryExecutionTask(
                    task.getTaskId(), task.getAttemptCount(), failureType, "Skill 执行失败，将自动重试",
                    RETRY_DELAY_SECONDS
            ) == 1) {
                try {
                    rabbitTemplate.convertAndSend(
                            RabbitMQConfig.BUILTIN_SKILL_TASK_RETRY_EXCHANGE,
                            RabbitMQConfig.BUILTIN_SKILL_TASK_RETRY_ROUTING_KEY,
                            task.getTaskId()
                    );
                } catch (RuntimeException exception) {
                    log.error("Builtin Skill retry publish failed; task remains retryable in PostgreSQL: {}",
                            exception.getClass().getName());
                }
            }
            return;
        }

        if (taskMapper.deadLetterExecutionTask(
                task.getTaskId(), task.getAttemptCount(), failureType, "任务已达到最大尝试次数"
        ) == 1) {
            publishDeadLetter(task.getTaskId());
        }
    }

    private void publishDeadLetterIfNeeded(String taskId) {
        BuiltinSkillExecutionTask task = taskMapper.selectByTaskId(taskId);
        if (task != null && "DEAD_LETTER".equals(task.getStatus())) {
            publishDeadLetter(taskId);
        }
    }

    private void publishDeadLetter(String taskId) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.BUILTIN_SKILL_TASK_DLX,
                RabbitMQConfig.BUILTIN_SKILL_TASK_DLQ_ROUTING_KEY,
                taskId
        );
    }

    private String normalizeTaskId(String taskId) {
        if (taskId != null && taskId.length() >= 2
                && taskId.startsWith("\"") && taskId.endsWith("\"")) {
            return taskId.substring(1, taskId.length() - 1);
        }
        return taskId;
    }
}
