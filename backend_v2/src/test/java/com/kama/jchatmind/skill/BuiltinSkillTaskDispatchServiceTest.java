package com.kama.jchatmind.skill;

import com.kama.jchatmind.mapper.BuiltinSkillTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BuiltinSkillTaskDispatchServiceTest {

    @Test
    void recoversExpiredExecutionLeasesThroughTheDatabaseStateTransition() {
        BuiltinSkillTaskMapper taskMapper = mock(BuiltinSkillTaskMapper.class);
        BuiltinSkillTaskPublisher taskPublisher = mock(BuiltinSkillTaskPublisher.class);
        when(taskMapper.recoverExpiredExecutionTasks(30)).thenReturn(1);
        BuiltinSkillTaskDispatchService service = new BuiltinSkillTaskDispatchService(taskMapper, taskPublisher);

        service.recoverExpiredExecutionLeases();

        verify(taskMapper).recoverExpiredExecutionTasks(30);
    }

    @Test
    void publishesOnlyTasksWhoseDatabaseLeaseWasAcquired() {
        BuiltinSkillTaskMapper taskMapper = mock(BuiltinSkillTaskMapper.class);
        BuiltinSkillTaskPublisher taskPublisher = mock(BuiltinSkillTaskPublisher.class);
        when(taskMapper.selectDispatchableTaskIds(64)).thenReturn(List.of("task-1", "task-2"));
        when(taskMapper.acquireDispatchLease("task-1", 60)).thenReturn(1);
        when(taskMapper.acquireDispatchLease("task-2", 60)).thenReturn(0);
        BuiltinSkillTaskDispatchService service = new BuiltinSkillTaskDispatchService(taskMapper, taskPublisher);

        service.dispatchPendingTasks();

        verify(taskPublisher).publish("task-1");
        verifyNoMoreInteractions(taskPublisher);
    }

    @Test
    void continuesWithOtherTasksWhenOnePublishFails() {
        BuiltinSkillTaskMapper taskMapper = mock(BuiltinSkillTaskMapper.class);
        BuiltinSkillTaskPublisher taskPublisher = mock(BuiltinSkillTaskPublisher.class);
        when(taskMapper.selectDispatchableTaskIds(64)).thenReturn(List.of("task-1", "task-2"));
        when(taskMapper.acquireDispatchLease("task-1", 60)).thenReturn(1);
        when(taskMapper.acquireDispatchLease("task-2", 60)).thenReturn(1);
        org.mockito.Mockito.doThrow(new AmqpException("broker unavailable"))
                .when(taskPublisher).publish("task-1");
        BuiltinSkillTaskDispatchService service = new BuiltinSkillTaskDispatchService(taskMapper, taskPublisher);

        service.dispatchPendingTasks();

        verify(taskPublisher).publish("task-1");
        verify(taskPublisher).publish("task-2");
    }
}
