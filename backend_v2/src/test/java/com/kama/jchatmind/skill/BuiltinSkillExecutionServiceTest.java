package com.kama.jchatmind.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.controller.BuiltinSkillController;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.response.GetChatSessionResponse;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BuiltinSkillExecutionServiceTest {

    @Test
    void canExecuteAnAlreadyPreparedInvocation() {
        BuiltinSkillExecutor executor = mock(BuiltinSkillExecutor.class);
        PreparedSkillInvocation invocation = new BuiltinSkillRegistry().prepare(
                "technical-decision-comparison", Map.of("question", "compare"), List.of("kb-1")
        );
        BuiltinSkillExecutionService.PreparedSkillExecution prepared =
                new BuiltinSkillExecutionService.PreparedSkillExecution(
                        "42", "agent-1", "session-1", invocation.definition().id(), invocation
                );
        Map<String, Object> output = Map.of("abstained", true, "reason", "no evidence");
        when(executor.execute("42", "session-1", invocation)).thenReturn(new SkillExecutionResult(
                output, SkillOutputValidationResult.accepted()
        ));
        BuiltinSkillExecutionService service = new BuiltinSkillExecutionService(
                mock(ChatSessionFacadeService.class),
                mock(AgentMapper.class),
                mock(AgentKnowledgeBaseBindingService.class),
                mock(KnowledgeBaseAccessService.class),
                executor,
                new ObjectMapper()
        );

        BuiltinSkillExecutionService.ExecutionResponse result = service.execute(prepared);

        assertThat(result.output()).isEqualTo(output);
        assertThat(result.valid()).isTrue();
        verify(executor).execute("42", "session-1", invocation);
    }

    @Test
    void preparesAnAuthorizedInvocationWithoutExecutingTheKnowledgeTool() throws Exception {
        ChatSessionFacadeService sessions = mock(ChatSessionFacadeService.class);
        AgentMapper agents = mock(AgentMapper.class);
        AgentKnowledgeBaseBindingService bindings = mock(AgentKnowledgeBaseBindingService.class);
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        AtomicReference<List<String>> executedKnowledgeBaseIds = new AtomicReference<>();
        BuiltinSkillExecutor executor = new BuiltinSkillExecutor(new BuiltinSkillRegistry(),
                (userId, sessionId, question, knowledgeBaseIds) -> {
                    executedKnowledgeBaseIds.set(knowledgeBaseIds);
                    return SkillKnowledgeToolResult.blocked("should not execute during prepare");
                });
        when(sessions.getChatSession("session-1", "42")).thenReturn(ownedSession("agent-1"));
        when(agents.selectById("agent-1")).thenReturn(agent("42", "[\"KnowledgeTool\"]"));
        when(bindings.getBoundKnowledgeBaseIds("agent-1")).thenReturn(List.of("kb-1", "kb-2"));
        when(access.requireAccessibleKnowledgeBaseIds(List.of("kb-1", "kb-2"), "42"))
                .thenReturn(List.of("kb-1", "kb-2"));
        BuiltinSkillExecutionService service = new BuiltinSkillExecutionService(
                sessions, agents, bindings, access, executor, new ObjectMapper()
        );

        BuiltinSkillExecutionService.PreparedSkillExecution prepared = service.prepare(
                42L,
                "agent-1",
                "session-1",
                "technical-decision-comparison",
                Map.of("question", "  比较缓存方案  ", "kbIds", List.of("kb-2"))
        );

        assertThat(prepared.userId()).isEqualTo("42");
        assertThat(prepared.agentId()).isEqualTo("agent-1");
        assertThat(prepared.sessionId()).isEqualTo("session-1");
        assertThat(prepared.skillId()).isEqualTo("technical-decision-comparison");
        assertThat(prepared.invocation().definition().version()).isEqualTo("v1");
        assertThat(prepared.invocation().input()).containsEntry("question", "比较缓存方案");
        assertThat(prepared.invocation().knowledgeBaseIds()).containsExactly("kb-2");
        assertThat(executedKnowledgeBaseIds.get()).isNull();
    }

    @Test
    void controllerUsesTheAuthenticatedUserAndDoesNotAcceptCallerKnowledgeScope() {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(42L);
        BuiltinSkillExecutionService service = mock(BuiltinSkillExecutionService.class);
        Map<String, Object> input = Map.of("question", "比较缓存方案", "kbIds", List.of("kb-2"));
        BuiltinSkillExecutionService.ExecutionResponse executionResponse =
                new BuiltinSkillExecutionService.ExecutionResponse(
                        Map.of("abstained", true, "reason", "no evidence"), true, List.of()
                );
        when(service.execute(42L, "agent-1", "session-1", "technical-decision-comparison", input))
                .thenReturn(executionResponse);
        BuiltinSkillController controller = new BuiltinSkillController(requestScopeData, service);

        ApiResponse<BuiltinSkillExecutionService.ExecutionResponse> response = controller.execute(
                "agent-1",
                "technical-decision-comparison",
                new BuiltinSkillController.ExecuteSkillRequest("session-1", input)
        );

        assertThat(response.getData()).isEqualTo(executionResponse);
        verify(service).execute(42L, "agent-1", "session-1", "technical-decision-comparison", input);
    }

    @Test
    void controllerRejectsAnonymousSkillRequestsBeforeCallingTheService() {
        BuiltinSkillExecutionService service = mock(BuiltinSkillExecutionService.class);
        BuiltinSkillController controller = new BuiltinSkillController(new RequestScopeData(), service);

        assertThatThrownBy(() -> controller.execute(
                "agent-1",
                "technical-decision-comparison",
                new BuiltinSkillController.ExecuteSkillRequest("session-1", Map.of("question", "比较方案"))
        )).isInstanceOf(BizException.class)
                .hasMessage("用户未登录");

        verifyNoInteractions(service);
    }

    @Test
    void executesOnlyWithinOwnedSessionAgentAndBoundKnowledgeBaseScope() throws Exception {
        ChatSessionFacadeService sessions = mock(ChatSessionFacadeService.class);
        AgentMapper agents = mock(AgentMapper.class);
        AgentKnowledgeBaseBindingService bindings = mock(AgentKnowledgeBaseBindingService.class);
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        AtomicReference<List<String>> queriedKbIds = new AtomicReference<>();
        BuiltinSkillExecutor executor = new BuiltinSkillExecutor(new BuiltinSkillRegistry(),
                (userId, sessionId, question, knowledgeBaseIds) -> {
                    queriedKbIds.set(knowledgeBaseIds);
                    return SkillKnowledgeToolResult.executed(List.of(retrievalResult("chunk-2", "kb-2")));
                });
        when(sessions.getChatSession("session-1", "42")).thenReturn(ownedSession("agent-1"));
        when(agents.selectById("agent-1")).thenReturn(agent("42", "[\"KnowledgeTool\"]"));
        when(bindings.getBoundKnowledgeBaseIds("agent-1")).thenReturn(List.of("kb-1", "kb-2"));
        when(access.requireAccessibleKnowledgeBaseIds(List.of("kb-1", "kb-2"), "42"))
                .thenReturn(List.of("kb-1", "kb-2"));

        BuiltinSkillExecutionService service = new BuiltinSkillExecutionService(
                sessions, agents, bindings, access, executor, new ObjectMapper()
        );

        BuiltinSkillExecutionService.ExecutionResponse result = service.execute(
                42L,
                "agent-1",
                "session-1",
                "technical-decision-comparison",
                Map.of("question", "比较缓存方案", "kbIds", List.of("kb-2"))
        );

        assertThat(result.valid()).isTrue();
        assertThat(result.output()).containsEntry("evidence", List.of(Map.of("chunkId", "chunk-2", "kbId", "kb-2")));
        assertThat(queriedKbIds.get()).containsExactly("kb-2");
        verify(access).requireAccessibleKnowledgeBaseIds(List.of("kb-1", "kb-2"), "42");
    }

    @Test
    void rejectsASessionBoundToAnotherAgentBeforeCallingTheSkill() {
        ChatSessionFacadeService sessions = mock(ChatSessionFacadeService.class);
        AgentMapper agents = mock(AgentMapper.class);
        AgentKnowledgeBaseBindingService bindings = mock(AgentKnowledgeBaseBindingService.class);
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        BuiltinSkillExecutor executor = mock(BuiltinSkillExecutor.class);
        when(sessions.getChatSession("session-1", "42")).thenReturn(ownedSession("agent-other"));
        BuiltinSkillExecutionService service = new BuiltinSkillExecutionService(
                sessions, agents, bindings, access, executor, new ObjectMapper()
        );

        assertThatThrownBy(() -> service.execute(
                42L, "agent-1", "session-1", "technical-decision-comparison", Map.of("question", "比较方案")
        )).hasMessage("无权访问会话");

        verifyNoInteractions(agents, bindings, access, executor);
    }

    @Test
    void rejectsAnAgentWithoutKnowledgeToolBeforeResolvingItsBindings() {
        ChatSessionFacadeService sessions = mock(ChatSessionFacadeService.class);
        AgentMapper agents = mock(AgentMapper.class);
        AgentKnowledgeBaseBindingService bindings = mock(AgentKnowledgeBaseBindingService.class);
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        BuiltinSkillExecutor executor = mock(BuiltinSkillExecutor.class);
        when(sessions.getChatSession("session-1", "42")).thenReturn(ownedSession("agent-1"));
        when(agents.selectById("agent-1")).thenReturn(agent("42", "[\"WeatherTool\"]"));
        BuiltinSkillExecutionService service = new BuiltinSkillExecutionService(
                sessions, agents, bindings, access, executor, new ObjectMapper()
        );

        assertThatThrownBy(() -> service.execute(
                42L, "agent-1", "session-1", "technical-decision-comparison", Map.of("question", "比较方案")
        )).hasMessage("Agent 未授权知识检索工具");

        verify(bindings, never()).getBoundKnowledgeBaseIds("agent-1");
        verifyNoInteractions(access, executor);
    }

    private GetChatSessionResponse ownedSession(String agentId) {
        return GetChatSessionResponse.builder()
                .chatSession(ChatSessionVO.builder().id("session-1").agentId(agentId).build())
                .build();
    }

    private Agent agent(String ownerId, String allowedToolsJson) {
        return Agent.builder()
                .id("agent-1")
                .userId(ownerId)
                .allowedTools(allowedToolsJson)
                .build();
    }

    private com.kama.jchatmind.model.dto.RagRetrievalResult retrievalResult(String chunkId, String kbId) {
        var result = new com.kama.jchatmind.model.dto.RagRetrievalResult();
        result.setChunkId(chunkId);
        result.setKbId(kbId);
        return result;
    }
}
