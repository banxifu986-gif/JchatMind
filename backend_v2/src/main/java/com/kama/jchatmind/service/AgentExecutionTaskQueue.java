package com.kama.jchatmind.service;

import com.kama.jchatmind.mapper.AgentExecutionTaskMapper;
import com.kama.jchatmind.model.dto.AgentExecutionTask;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@AllArgsConstructor
public class AgentExecutionTaskQueue {

    private final AgentExecutionTaskMapper agentExecutionTaskMapper;

    public List<AgentExecutionTask> findRunnableSessionHeads(int limit) {
        return agentExecutionTaskMapper.selectRunnableSessionHeads(limit);
    }

    public long countByStatus(String status) {
        return agentExecutionTaskMapper.countByStatus(status);
    }

    @Transactional
    public AgentExecutionTask claim(String id) {
        return agentExecutionTaskMapper.claim(id);
    }

    @Transactional
    public void release(String id) {
        requireOne(agentExecutionTaskMapper.release(id));
    }

    @Transactional
    public void complete(String id) {
        requireOne(agentExecutionTaskMapper.complete(id));
    }

    @Transactional
    public void fail(String id, String failureType) {
        requireOne(agentExecutionTaskMapper.fail(id, failureType));
    }

    private void requireOne(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("Agent execution task state changed unexpectedly");
        }
    }
}
