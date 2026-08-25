package com.kama.jchatmind.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.agent.harness.approval.InMemoryApprovalStore;
import com.kama.jchatmind.agent.harness.audit.InMemoryAuditStore;
import com.kama.jchatmind.agent.harness.circuit.InMemoryCircuitBreakerRegistry;
import com.kama.jchatmind.agent.harness.interceptor.AuditTrailInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.CircuitBreakerInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.agent.harness.interceptor.HumanApprovalInterceptor;
import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HarnessedSkillKnowledgeToolExecutorTest {

    @Test
    void shouldNotReachKnowledgeRetrievalWhenHarnessCircuitBreakerRejectsKnowledgeTool() {
        HarnessProperties properties = harnessProperties();
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        InMemoryCircuitBreakerRegistry circuitRegistry = new InMemoryCircuitBreakerRegistry(properties);
        circuitRegistry.get("KnowledgeTool").recordFailure();
        HarnessInterceptorChain interceptorChain = new HarnessInterceptorChain(List.of(
                new CircuitBreakerInterceptor(properties, circuitRegistry),
                new AuditTrailInterceptor(properties, auditStore)
        ));
        HarnessRunner harnessRunner = new HarnessRunner(properties, interceptorChain, approvalStore, auditStore);
        KnowledgeTools knowledgeTools = mock(KnowledgeTools.class);
        HarnessedSkillKnowledgeToolExecutor executor = new HarnessedSkillKnowledgeToolExecutor(
                knowledgeTools,
                harnessRunner,
                interceptorChain,
                new ObjectMapper()
        );

        SkillKnowledgeToolResult result = executor.execute(
                "user-1",
                "session-1",
                "比较两种缓存方案",
                List.of("kb-1")
        );

        assertThat(result.executed()).isFalse();
        assertThat(result.reason()).contains("CIRCUIT_BREAKER_OPEN");
        verifyNoInteractions(knowledgeTools);
        assertThat(auditStore.getBySession("session-1"))
                .singleElement()
                .satisfies(record -> assertThat(record.getOutcome().name()).isEqualTo("CIRCUIT_OPEN"));
    }

    @Test
    void shouldNotReachKnowledgeRetrievalWhenHarnessApprovalExpires() {
        HarnessProperties properties = harnessProperties();
        properties.getHumanApproval().setEnabled(true);
        properties.getHumanApproval().setTools(List.of("KnowledgeTool"));
        properties.getHumanApproval().setTimeoutSeconds(0);
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        InMemoryCircuitBreakerRegistry circuitRegistry = new InMemoryCircuitBreakerRegistry(properties);
        HarnessInterceptorChain interceptorChain = new HarnessInterceptorChain(List.of(
                new CircuitBreakerInterceptor(properties, circuitRegistry),
                new HumanApprovalInterceptor(properties, approvalStore),
                new AuditTrailInterceptor(properties, auditStore)
        ));
        HarnessRunner harnessRunner = new HarnessRunner(properties, interceptorChain, approvalStore, auditStore);
        KnowledgeTools knowledgeTools = mock(KnowledgeTools.class);
        HarnessedSkillKnowledgeToolExecutor executor = new HarnessedSkillKnowledgeToolExecutor(
                knowledgeTools,
                harnessRunner,
                interceptorChain,
                new ObjectMapper()
        );

        SkillKnowledgeToolResult result = executor.execute(
                "user-1",
                "session-1",
                "比较两种缓存方案",
                List.of("kb-1")
        );

        assertThat(result.executed()).isFalse();
        assertThat(result.reason()).contains("APPROVAL_EXPIRED");
        verifyNoInteractions(knowledgeTools);
        assertThat(auditStore.getBySession("session-1"))
                .singleElement()
                .satisfies(record -> assertThat(record.getOutcome().name()).isEqualTo("EXPIRED"));
    }

    @Test
    void shouldExecuteTheBoundKnowledgeToolAndRecordTheHarnessSuccessOutcome() {
        HarnessProperties properties = harnessProperties();
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        InMemoryCircuitBreakerRegistry circuitRegistry = new InMemoryCircuitBreakerRegistry(properties);
        HarnessInterceptorChain interceptorChain = new HarnessInterceptorChain(List.of(
                new CircuitBreakerInterceptor(properties, circuitRegistry),
                new AuditTrailInterceptor(properties, auditStore)
        ));
        HarnessRunner harnessRunner = new HarnessRunner(properties, interceptorChain, approvalStore, auditStore);
        KnowledgeTools knowledgeTools = mock(KnowledgeTools.class);
        KnowledgeTools boundKnowledgeTools = mock(KnowledgeTools.class);
        RagRetrievalResult retrievalResult = new RagRetrievalResult();
        retrievalResult.setChunkId("chunk-1");
        retrievalResult.setKbId("kb-1");
        when(knowledgeTools.fork(eq("user-1"), eq("session-1"), anyList())).thenReturn(boundKnowledgeTools);
        when(boundKnowledgeTools.retrieveKnowledge("比较两种缓存方案", List.of("kb-1")))
                .thenReturn(List.of(retrievalResult));
        HarnessedSkillKnowledgeToolExecutor executor = new HarnessedSkillKnowledgeToolExecutor(
                knowledgeTools,
                harnessRunner,
                interceptorChain,
                new ObjectMapper()
        );

        SkillKnowledgeToolResult result = executor.execute(
                "user-1",
                "session-1",
                "比较两种缓存方案",
                List.of("kb-1")
        );

        assertThat(result.executed()).isTrue();
        assertThat(result.results()).containsExactly(retrievalResult);
        verify(knowledgeTools).fork(eq("user-1"), eq("session-1"), anyList());
        verify(boundKnowledgeTools).retrieveKnowledge("比较两种缓存方案", List.of("kb-1"));
        assertThat(auditStore.getBySession("session-1"))
                .singleElement()
                .satisfies(record -> assertThat(record.getOutcome().name()).isEqualTo("SUCCESS"));
    }

    private HarnessProperties harnessProperties() {
        HarnessProperties properties = new HarnessProperties();
        properties.getCircuitBreaker().setEnabled(true);
        properties.getCircuitBreaker().setTools(List.of("KnowledgeTool"));
        properties.getCircuitBreaker().setFailureThreshold(1);
        properties.getCircuitBreaker().setRecoveryTimeoutSeconds(30);
        properties.getAudit().setEnabled(true);
        properties.getAudit().setMaxRecordsPerSession(100);
        return properties;
    }
}
