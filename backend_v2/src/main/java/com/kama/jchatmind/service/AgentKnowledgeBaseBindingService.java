package com.kama.jchatmind.service;

import com.kama.jchatmind.mapper.AgentKnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentKnowledgeBaseBindingService {

    private final AgentKnowledgeBaseMapper agentKnowledgeBaseMapper;

    public List<String> getBoundKnowledgeBaseIds(String agentId) {
        return agentKnowledgeBaseMapper.selectKbIdsByAgentId(agentId);
    }

    public void replaceBindings(String agentId, List<String> kbIds, String boundByUserId) {
        agentKnowledgeBaseMapper.deleteByAgentId(agentId);
        if (!CollectionUtils.isEmpty(kbIds)) {
            agentKnowledgeBaseMapper.insertBatch(agentId, kbIds, boundByUserId);
        }
    }
}
