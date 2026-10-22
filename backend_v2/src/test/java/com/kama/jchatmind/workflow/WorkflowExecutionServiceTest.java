package com.kama.jchatmind.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.response.GetChatSessionResponse;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.webhook.WebhookProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowExecutionServiceTest {

    @Test
    void shouldUseOwnerSessionAndAuthorizedKnowledgeEvidenceForExplicitWorkflow() {
        AgentMapper agentMapper = mock(AgentMapper.class);
        ChatSessionFacadeService sessionService = mock(ChatSessionFacadeService.class);
        AgentKnowledgeBaseBindingService bindingService = mock(AgentKnowledgeBaseBindingService.class);
        KnowledgeBaseAccessService accessService = mock(KnowledgeBaseAccessService.class);
        RagService ragService = mock(RagService.class);
        WorkflowPlanner planner = mock(WorkflowPlanner.class);
        WorkflowPlanExecutor executor = mock(WorkflowPlanExecutor.class);
        WorkflowExecutionAuditRecorder auditRecorder = mock(WorkflowExecutionAuditRecorder.class);
        WorkflowSingleAgentFallbackAnswerService fallback = mock(WorkflowSingleAgentFallbackAnswerService.class);
        WebhookProperties webhookProperties = new WebhookProperties();
        WebhookProperties.Source source = new WebhookProperties.Source();
        source.setOwnerId(7L);
        source.setAllowedKbIds(java.util.Set.of("kb-owned"));
        source.setInboundSigningKey("inbound-secret");
        source.setCallbackUrl("https://callback.example.invalid/endpoint");
        source.setOutboundSigningKey("outbound-secret");
        webhookProperties.getSources().put("build-system", source);
        WebhookProperties.Source foreignSource = new WebhookProperties.Source();
        foreignSource.setOwnerId(8L);
        foreignSource.setAllowedKbIds(java.util.Set.of("kb-owned"));
        foreignSource.setInboundSigningKey("foreign-inbound-secret");
        foreignSource.setCallbackUrl("https://foreign-callback.example.invalid/endpoint");
        foreignSource.setOutboundSigningKey("foreign-outbound-secret");
        webhookProperties.getSources().put("foreign-source", foreignSource);

        Agent agent = Agent.builder()
                .id("agent-1")
                .userId("7")
                .model("model-key")
                .allowedTools("[\"KnowledgeTool\"]")
                .build();
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        when(sessionService.getChatSession("session-1", "7")).thenReturn(GetChatSessionResponse.builder()
                .chatSession(ChatSessionVO.builder().id("session-1").agentId("agent-1").build())
                .build());
        when(bindingService.getBoundKnowledgeBaseIds("agent-1")).thenReturn(List.of("kb-owned", "kb-other"));
        when(accessService.requireAccessibleKnowledgeBaseIds(List.of("kb-owned", "kb-other"), "7"))
                .thenReturn(List.of("kb-owned", "kb-other"));
        RagRetrievalResult retrieved = new RagRetrievalResult();
        retrieved.setChunkId("chunk-1");
        retrieved.setContent("authorized evidence");
        when(ragService.retrieve(List.of("kb-owned"), "question", 10)).thenReturn(List.of(retrieved));
        WorkflowPlan plan = new WorkflowPlan(1, 10, List.of(new WorkflowStep(
                "step-1", "KnowledgeTool", "fact-1", "claim", List.of(
                new WorkflowEvidence("chunk-1", "fact-1", "authorized evidence")
        ))));
        when(planner.plan(eq("model-key"), eq("question"), eq(List.of("KnowledgeTool")), any()))
                .thenReturn(new WorkflowPlanPlanningResult(true, plan, List.of()));
        WorkflowExecutionResult execution = WorkflowExecutionResult.completed(List.of(
                new WorkflowStepExecution("step-1", WorkflowStepExecutionStatus.SUCCEEDED, "authorized evidence")
        ));
        when(executor.executeForWebhookSource(eq("7"), eq("agent-1"), eq("session-1"), eq("build-system"), eq(plan),
                eq(List.of("KnowledgeTool")), any())).thenReturn(execution);

        WorkflowExecutionService service = new WorkflowExecutionService(
                agentMapper,
                sessionService,
                bindingService,
                accessService,
                ragService,
                planner,
                executor,
                auditRecorder,
                fallback,
                webhookProperties,
                new ObjectMapper()
        );

        WorkflowExecutionService.WorkflowExecutionResponse response = service.execute(
                7L,
                new WorkflowExecutionService.WorkflowExecutionCommand(
                        "agent-1", "session-1", "question", "build-system"
                )
        );

        assertThat(response.mode()).isEqualTo(WorkflowOrchestrationMode.WORKFLOW.name());
        assertThat(response.workflowExecution()).isSameAs(execution);
        verify(accessService).requireAccessibleKnowledgeBaseIds(List.of("kb-owned", "kb-other"), "7");
        verify(ragService).retrieve(List.of("kb-owned"), "question", 10);
        verify(planner).plan(eq("model-key"), eq("question"), eq(List.of("KnowledgeTool")), any());
        verify(executor).executeForWebhookSource(eq("7"), eq("agent-1"), eq("session-1"), eq("build-system"), eq(plan),
                eq(List.of("KnowledgeTool")), any());
        verify(fallback, never()).answer(any(), any(), any());
        verify(auditRecorder).record(eq(response.traceId()), eq(7L), eq("question"), eq(plan), eq(execution), any(Long.class));

        doThrow(new IllegalStateException("secret tool/provider details")).when(executor).executeForWebhookSource(
                eq("7"), eq("agent-1"), eq("session-1"), eq("build-system"), eq(plan),
                eq(List.of("KnowledgeTool")), any()
        );
        WorkflowExecutionService.WorkflowExecutionResponse failedExecution = service.execute(
                7L,
                new WorkflowExecutionService.WorkflowExecutionCommand(
                        "agent-1", "session-1", "question", "build-system"
                )
        );
        assertThat(failedExecution.mode()).isEqualTo(WorkflowOrchestrationMode.WORKFLOW.name());
        assertThat(failedExecution.workflowExecution().steps())
                .singleElement()
                .satisfies(step -> assertThat(step.output())
                        .isEqualTo("工作流执行失败")
                        .doesNotContain("secret tool/provider details"));
        verify(auditRecorder).record(
                eq(failedExecution.traceId()),
                eq(7L),
                eq("question"),
                eq(plan),
                eq(failedExecution.workflowExecution()),
                any(Long.class)
        );
        verify(fallback, never()).answer(any(), any(), any());

        assertThatThrownBy(() -> service.execute(
                7L,
                new WorkflowExecutionService.WorkflowExecutionCommand(
                        "agent-1",
                        "session-1",
                        IntStream.range(0, 16_385).mapToObj(index -> "x").collect(Collectors.joining()),
                        "build-system"
                )
        )).hasMessage("工作流请求无效");
        assertThatThrownBy(() -> service.execute(
                7L,
                new WorkflowExecutionService.WorkflowExecutionCommand(
                        "agent-1", "session-1", "question", "foreign-source"
                )
        )).hasMessage("无权访问 Webhook 来源");
        verify(ragService).retrieve(List.of("kb-owned"), "question", 10);
    }
}
