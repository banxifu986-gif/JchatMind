package com.kama.jchatmind.skill;

import com.kama.jchatmind.mapper.BuiltinSkillTaskMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@AllArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "builtin-skill.task.dispatch", name = "enabled", havingValue = "true")
public class BuiltinSkillTaskDispatchService {

    private static final int DISPATCH_BATCH_LIMIT = 64;
    private static final int DISPATCH_LEASE_SECONDS = 60;
    private static final int RECOVERY_RETRY_DELAY_SECONDS = 30;

    private final BuiltinSkillTaskMapper taskMapper;
    private final BuiltinSkillTaskPublisher taskPublisher;

    @Scheduled(fixedDelay = 30_000)
    public void recoverExpiredExecutionLeases() {
        try {
            int recovered = taskMapper.recoverExpiredExecutionTasks(RECOVERY_RETRY_DELAY_SECONDS);
            if (recovered > 0) {
                log.info("Recovered expired Builtin Skill execution leases: {}", recovered);
            }
        } catch (RuntimeException exception) {
            log.error("Builtin Skill execution lease recovery failed: {}", exception.getClass().getName());
        }
    }

    @Scheduled(fixedDelay = 250)
    public void dispatchPendingTasks() {
        List<String> taskIds;
        try {
            taskIds = taskMapper.selectDispatchableTaskIds(DISPATCH_BATCH_LIMIT);
        } catch (RuntimeException exception) {
            log.error("Builtin Skill task dispatch scan failed: {}", exception.getClass().getName());
            return;
        }

        for (String taskId : taskIds) {
            try {
                if (taskMapper.acquireDispatchLease(taskId, DISPATCH_LEASE_SECONDS) != 1) {
                    continue;
                }
                taskPublisher.publish(taskId);
            } catch (RuntimeException exception) {
                log.error("Builtin Skill task dispatch failed: {}", exception.getClass().getName());
            }
        }
    }
}
