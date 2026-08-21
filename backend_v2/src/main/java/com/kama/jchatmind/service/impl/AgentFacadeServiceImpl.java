package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.request.CreateAgentRequest;
import com.kama.jchatmind.model.request.UpdateAgentRequest;
import com.kama.jchatmind.model.response.CreateAgentResponse;
import com.kama.jchatmind.model.response.GetAgentsResponse;
import com.kama.jchatmind.model.vo.AgentVO;
import com.kama.jchatmind.service.AgentFacadeService;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Service
@AllArgsConstructor
public class AgentFacadeServiceImpl implements AgentFacadeService {

    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;
    private final RequestScopeData requestScopeData;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final AgentKnowledgeBaseBindingService agentKnowledgeBaseBindingService;

    @Override
    public GetAgentsResponse getAgents() {
        String userId = requireUserId();
        List<Agent> agents = agentMapper.selectByUserId(userId);
        List<AgentVO> result = new ArrayList<>();
        for (Agent agent : agents) {
            try {
                AgentVO vo = agentConverter.toVO(agent);
                vo.setAllowedKbs(agentKnowledgeBaseBindingService.getBoundKnowledgeBaseIds(agent.getId()));
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetAgentsResponse.builder()
                .agents(result.toArray(new AgentVO[0]))
                .build();
    }

    @Override
    @Transactional
    public CreateAgentResponse createAgent(CreateAgentRequest request) {
        try {
            String userId = requireUserId();

            // 将 CreateAgentRequest 转换为 AgentDTO
            AgentDTO agentDTO = agentConverter.toDTO(request);
            agentDTO.setUserId(userId);
            agentDTO.setAllowedKbs(knowledgeBaseAccessService
                    .requireAccessibleKnowledgeBaseIds(agentDTO.getAllowedKbs(), userId));

            // 将 AgentDTO 转换为 Agent 实体
            Agent agent = agentConverter.toEntity(agentDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            agent.setCreatedAt(now);
            agent.setUpdatedAt(now);

            // 插入数据库，ID 由数据库自动生成
            int result = agentMapper.insert(agent);
            if (result <= 0) {
                throw new BizException("创建 agent 失败");
            }
            agentKnowledgeBaseBindingService.replaceBindings(
                    agent.getId(),
                    agentDTO.getAllowedKbs(),
                    userId
            );

            // 返回生成的 agentId
            return CreateAgentResponse.builder()
                    .agentId(agent.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建 agent 时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public void deleteAgent(String agentId) {
        Agent agent = requireOwnedAgent(agentId);

        int result = agentMapper.deleteById(agent.getId());
        if (result <= 0) {
            throw new BizException("删除 agent 失败");
        }
    }

    @Override
    @Transactional
    public void updateAgent(String agentId, UpdateAgentRequest request) {
        try {
            String userId = requireUserId();
            Agent existingAgent = requireOwnedAgent(agentId);

            // 将现有 Agent 转换为 AgentDTO
            AgentDTO agentDTO = agentConverter.toDTO(existingAgent);
            agentDTO.setAllowedKbs(agentKnowledgeBaseBindingService
                    .getBoundKnowledgeBaseIds(existingAgent.getId()));

            // 使用 UpdateAgentRequest 更新 AgentDTO
            agentConverter.updateDTOFromRequest(agentDTO, request);
            agentDTO.setAllowedKbs(knowledgeBaseAccessService
                    .requireAccessibleKnowledgeBaseIds(agentDTO.getAllowedKbs(), userId));

            // 将更新后的 AgentDTO 转换回 Agent 实体
            Agent updatedAgent = agentConverter.toEntity(agentDTO);

            // 保留原有的 ID、userId 和创建时间
            updatedAgent.setId(existingAgent.getId());
            updatedAgent.setUserId(userId);
            updatedAgent.setCreatedAt(existingAgent.getCreatedAt());
            updatedAgent.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = agentMapper.updateById(updatedAgent);
            if (result <= 0) {
                throw new BizException("更新 agent 失败");
            }
            agentKnowledgeBaseBindingService.replaceBindings(
                    updatedAgent.getId(),
                    agentDTO.getAllowedKbs(),
                    userId
            );
        } catch (JsonProcessingException e) {
            throw new BizException("更新 agent 时发生序列化错误: " + e.getMessage());
        }
    }

    private String requireUserId() {
        Long userId = requestScopeData.getUserId();
        if (userId == null) {
            throw new BizException("用户未登录");
        }
        return String.valueOf(userId);
    }

    private Agent requireOwnedAgent(String agentId) {
        String userId = requireUserId();
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null || agent.getUserId() == null || !agent.getUserId().equals(userId)) {
            throw new BizException("无权访问 Agent");
        }
        return agent;
    }
}
