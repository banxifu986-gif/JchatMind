package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.mapper.AgentExecutionTaskMapper;
import com.kama.jchatmind.model.dto.AgentExecutionTask;
import lombok.AllArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.UUID;

@Component
@AllArgsConstructor
public class AgentExecutionTaskEnqueueListener {

    private final AgentExecutionTaskMapper agentExecutionTaskMapper;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(ChatEvent event) {
        Assert.hasText(event.getUserMessageId(), "User message id cannot be empty");
        Assert.notNull(event.getSequenceNo(), "Agent turn sequence cannot be null");
        AgentExecutionTask task = new AgentExecutionTask();
        task.setId(UUID.randomUUID().toString());
        task.setSessionId(event.getSessionId());
        task.setUserId(event.getUserId());
        task.setAgentId(event.getAgentId());
        task.setUserMessageId(event.getUserMessageId());
        task.setSequenceNo(event.getSequenceNo());
        task.setStatus("PENDING");
        if (agentExecutionTaskMapper.insert(task) != 1) {
            throw new IllegalStateException("Unable to persist agent execution task");
        }
    }
}
