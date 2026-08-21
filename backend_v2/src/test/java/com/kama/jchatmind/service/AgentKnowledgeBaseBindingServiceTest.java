package com.kama.jchatmind.service;

import com.kama.jchatmind.mapper.AgentKnowledgeBaseMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class AgentKnowledgeBaseBindingServiceTest {

    @Test
    void shouldReplaceAllPersistentBindingsForAnAgent() {
        AgentKnowledgeBaseMapper mapper = mock(AgentKnowledgeBaseMapper.class);
        AgentKnowledgeBaseBindingService service = new AgentKnowledgeBaseBindingService(mapper);

        service.replaceBindings("agent-1", List.of("kb-1", "kb-2"), "7");

        InOrder inOrder = inOrder(mapper);
        inOrder.verify(mapper).deleteByAgentId("agent-1");
        inOrder.verify(mapper).insertBatch("agent-1", List.of("kb-1", "kb-2"), "7");
    }
}
