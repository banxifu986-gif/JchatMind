package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.request.CreateAgentRequest;
import com.kama.jchatmind.model.request.UpdateAgentRequest;
import com.kama.jchatmind.model.vo.AgentVO;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentFacadeServiceImplTest {

    @Test
    void shouldRejectForeignKnowledgeBaseWhenCreatingAgent() {
        AgentMapper agentMapper = mock(AgentMapper.class);
        when(agentMapper.insert(any(Agent.class))).thenReturn(1);
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        when(knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(List.of("foreign-kb"), "7"))
                .thenThrow(new BizException("无权访问知识库"));
        AgentFacadeServiceImpl service = service(agentMapper, new AgentConverter(new ObjectMapper()), knowledgeBaseAccessService);

        assertThatThrownBy(() -> service.createAgent(createRequest(List.of("foreign-kb"))))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问知识库");
    }

    @Test
    void shouldRejectMissingKnowledgeBaseWhenUpdatingAgent() throws Exception {
        AgentMapper agentMapper = mock(AgentMapper.class);
        Agent existingAgent = Agent.builder()
                .id("agent-1")
                .userId("7")
                .name("agent")
                .model(AgentDTO.ModelType.DEEPSEEK_CHAT.getModelName())
                .allowedTools("[]")
                .chatOptions("{\"temperature\":0.7,\"topP\":1.0,\"messageLength\":10}")
                .build();
        when(agentMapper.selectById("agent-1")).thenReturn(existingAgent);
        when(agentMapper.updateById(any(Agent.class))).thenReturn(1);
        AgentConverter agentConverter = mock(AgentConverter.class);
        AgentDTO agentDTO = AgentDTO.builder()
                .allowedTools(List.of())
                .allowedKbs(List.of())
                .chatOptions(AgentDTO.ChatOptions.defaultOptions())
                .model(AgentDTO.ModelType.DEEPSEEK_CHAT)
                .build();
        when(agentConverter.toDTO(existingAgent)).thenReturn(agentDTO);
        when(agentConverter.toEntity(any(AgentDTO.class))).thenReturn(existingAgent);
        doAnswer(invocation -> {
            AgentDTO target = invocation.getArgument(0);
            UpdateAgentRequest updateRequest = invocation.getArgument(1);
            target.setAllowedKbs(updateRequest.getAllowedKbs());
            return null;
        }).when(agentConverter).updateDTOFromRequest(any(AgentDTO.class), any(UpdateAgentRequest.class));
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        when(knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(List.of("missing-kb"), "7"))
                .thenThrow(new BizException("无权访问知识库"));
        AgentFacadeServiceImpl service = service(agentMapper, agentConverter, knowledgeBaseAccessService);
        UpdateAgentRequest request = new UpdateAgentRequest();
        request.setAllowedKbs(List.of("missing-kb"));

        assertThatThrownBy(() -> service.updateAgent("agent-1", request))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问知识库");
    }

    @Test
    void shouldDeduplicateKnowledgeBaseBindingsBeforePersistingAgent() {
        AgentMapper agentMapper = mock(AgentMapper.class);
        doAnswer(invocation -> {
            invocation.<Agent>getArgument(0).setId("agent-1");
            return 1;
        }).when(agentMapper).insert(any(Agent.class));
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        when(knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(List.of("kb-1", "kb-1"), "7"))
                .thenReturn(List.of("kb-1"));
        AgentKnowledgeBaseBindingService bindingService = mock(AgentKnowledgeBaseBindingService.class);
        AgentFacadeServiceImpl service = service(
                agentMapper,
                new AgentConverter(new ObjectMapper()),
                knowledgeBaseAccessService,
                bindingService
        );

        service.createAgent(createRequest(List.of("kb-1", "kb-1")));

        verify(bindingService).replaceBindings("agent-1", List.of("kb-1"), "7");
    }

    @Test
    void shouldReadKnowledgeBaseBindingsFromRelationTable() throws Exception {
        AgentMapper agentMapper = mock(AgentMapper.class);
        Agent agent = existingAgent("agent-1");
        when(agentMapper.selectByUserId("7")).thenReturn(List.of(agent));
        AgentConverter agentConverter = mock(AgentConverter.class);
        when(agentConverter.toVO(agent)).thenReturn(AgentVO.builder().allowedKbs(List.of("legacy-kb")).build());
        AgentKnowledgeBaseBindingService bindingService = mock(AgentKnowledgeBaseBindingService.class);
        when(bindingService.getBoundKnowledgeBaseIds("agent-1")).thenReturn(List.of("relation-kb"));
        AgentFacadeServiceImpl service = service(
                agentMapper,
                agentConverter,
                mock(KnowledgeBaseAccessService.class),
                bindingService
        );

        assertThat(service.getAgents().getAgents()[0].getAllowedKbs()).containsExactly("relation-kb");
    }

    @Test
    void shouldPreserveRelationBindingsWhenUpdatingOtherAgentFields() throws Exception {
        AgentMapper agentMapper = mock(AgentMapper.class);
        Agent existingAgent = existingAgent("agent-1");
        when(agentMapper.selectById("agent-1")).thenReturn(existingAgent);
        when(agentMapper.updateById(any(Agent.class))).thenReturn(1);
        AgentConverter agentConverter = mock(AgentConverter.class);
        AgentDTO agentDTO = AgentDTO.builder()
                .allowedTools(List.of())
                .allowedKbs(List.of("legacy-kb"))
                .chatOptions(AgentDTO.ChatOptions.defaultOptions())
                .model(AgentDTO.ModelType.DEEPSEEK_CHAT)
                .build();
        when(agentConverter.toDTO(existingAgent)).thenReturn(agentDTO);
        when(agentConverter.toEntity(any(AgentDTO.class))).thenReturn(existingAgent);
        doAnswer(invocation -> {
            invocation.<AgentDTO>getArgument(0).setName(invocation.<UpdateAgentRequest>getArgument(1).getName());
            return null;
        }).when(agentConverter).updateDTOFromRequest(any(AgentDTO.class), any(UpdateAgentRequest.class));
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        when(knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(List.of("relation-kb"), "7"))
                .thenReturn(List.of("relation-kb"));
        AgentKnowledgeBaseBindingService bindingService = mock(AgentKnowledgeBaseBindingService.class);
        when(bindingService.getBoundKnowledgeBaseIds("agent-1")).thenReturn(List.of("relation-kb"));
        AgentFacadeServiceImpl service = service(
                agentMapper,
                agentConverter,
                knowledgeBaseAccessService,
                bindingService
        );
        UpdateAgentRequest request = new UpdateAgentRequest();
        request.setName("renamed");

        service.updateAgent("agent-1", request);

        verify(bindingService).replaceBindings("agent-1", List.of("relation-kb"), "7");
    }

    @Test
    void shouldNotRevealWhetherAgentExistsWhenUpdatingForeignAgent() {
        AgentMapper agentMapper = mock(AgentMapper.class);
        Agent foreignAgent = Agent.builder().id("agent-foreign").userId("8").build();
        when(agentMapper.selectById("agent-foreign")).thenReturn(foreignAgent);
        AgentFacadeServiceImpl service = service(
                agentMapper,
                mock(AgentConverter.class),
                mock(KnowledgeBaseAccessService.class)
        );

        assertThatThrownBy(() -> service.updateAgent("agent-foreign", new UpdateAgentRequest()))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问 Agent");
    }

    private AgentFacadeServiceImpl service(AgentMapper agentMapper) {
        return service(
                agentMapper,
                new AgentConverter(new ObjectMapper()),
                mock(KnowledgeBaseAccessService.class)
        );
    }

    private AgentFacadeServiceImpl service(
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseAccessService knowledgeBaseAccessService
    ) {
        return service(agentMapper, agentConverter, knowledgeBaseAccessService, mock(AgentKnowledgeBaseBindingService.class));
    }

    private AgentFacadeServiceImpl service(
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            AgentKnowledgeBaseBindingService bindingService
    ) {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);
        return new AgentFacadeServiceImpl(
                agentMapper,
                agentConverter,
                requestScopeData,
                knowledgeBaseAccessService,
                bindingService
        );
    }

    private CreateAgentRequest createRequest(List<String> allowedKbs) {
        CreateAgentRequest request = new CreateAgentRequest();
        request.setName("agent");
        request.setModel(AgentDTO.ModelType.DEEPSEEK_CHAT.getModelName());
        request.setAllowedTools(List.of());
        request.setAllowedKbs(allowedKbs);
        request.setChatOptions(AgentDTO.ChatOptions.defaultOptions());
        return request;
    }

    private Agent existingAgent(String id) {
        return Agent.builder()
                .id(id)
                .userId("7")
                .name("agent")
                .model(AgentDTO.ModelType.DEEPSEEK_CHAT.getModelName())
                .allowedTools("[]")
                .chatOptions("{\"temperature\":0.7,\"topP\":1.0,\"messageLength\":10}")
                .build();
    }
}
