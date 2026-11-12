package com.kama.jchatmind.service;

import com.kama.jchatmind.mapper.AgentKnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentKnowledgeBaseBindingDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentKnowledgeBaseBindingService {

    private final AgentKnowledgeBaseMapper agentKnowledgeBaseMapper;

    public List<String> getBoundKnowledgeBaseIds(String agentId) {
        return agentKnowledgeBaseMapper.selectKbIdsByAgentId(agentId);
    }

    public Map<String, List<String>> getBoundKnowledgeBaseIdsByAgentIds(List<String> agentIds) {
        if (CollectionUtils.isEmpty(agentIds)) {
            return Map.of();
        }
        Map<String, List<String>> bindings = new LinkedHashMap<>();
        for (AgentKnowledgeBaseBindingDTO binding : agentKnowledgeBaseMapper.selectKbIdsByAgentIds(agentIds)) {
            bindings.computeIfAbsent(binding.getAgentId(), ignored -> new ArrayList<>())
                    .add(binding.getKbId());
        }
        return bindings;
    }

    public void replaceBindings(String agentId, List<String> kbIds, String boundByUserId) {
        agentKnowledgeBaseMapper.deleteByAgentId(agentId);
        if (!CollectionUtils.isEmpty(kbIds)) {
            agentKnowledgeBaseMapper.insertBatch(agentId, kbIds, boundByUserId);
        }
    }
}
