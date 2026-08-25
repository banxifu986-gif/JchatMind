package com.kama.jchatmind.workflow;

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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowPlanExecutorTest {

    @Test
    void shouldExecuteVerifiedPlanStepsInDeclaredOrderThroughHarness() {
        InMemoryAuditStore auditStore = new InMemoryAuditStore(harnessProperties());
        WorkflowPlanExecutor executor = newExecutor(auditStore, new InMemoryCircuitBreakerRegistry(harnessProperties()));
        List<String> executedStepIds = new ArrayList<>();
        WorkflowPlan plan = new WorkflowPlan(
                2,
                30,
                List.of(
                        step("read-design", "KnowledgeTool", "design", "采用 Redis"),
                        step("read-runbook", "KnowledgeTool", "runbook", "缓存故障先检查命中率")
                )
        );

        WorkflowExecutionResult result = executor.execute(
                "user-1",
                "workflow-agent-1",
                "session-1",
                plan,
                List.of("KnowledgeTool"),
                step -> {
                    executedStepIds.add(step.id());
                    return "executed:" + step.id();
                }
        );

        assertThat(result.completed()).isTrue();
        assertThat(executedStepIds).containsExactly("read-design", "read-runbook");
        assertThat(result.steps()).extracting(WorkflowStepExecution::stepId)
                .containsExactly("read-design", "read-runbook");
        assertThat(result.steps()).extracting(WorkflowStepExecution::output)
                .containsExactly("executed:read-design", "executed:read-runbook");
        assertThat(auditStore.getBySession("session-1"))
                .extracting(record -> record.getOutcome().name())
                .containsExactly("SUCCESS", "SUCCESS");
    }

    @Test
    void shouldStopWorkflowBeforeBusinessExecutionWhenHarnessRejectsAStep() {
        HarnessProperties properties = harnessProperties();
        properties.getCircuitBreaker().setEnabled(true);
        properties.getCircuitBreaker().setTools(List.of("KnowledgeTool"));
        properties.getCircuitBreaker().setFailureThreshold(1);
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        InMemoryCircuitBreakerRegistry circuitRegistry = new InMemoryCircuitBreakerRegistry(properties);
        circuitRegistry.get("KnowledgeTool").recordFailure();
        WorkflowPlanExecutor executor = newExecutor(auditStore, circuitRegistry, properties);
        WorkflowPlan plan = new WorkflowPlan(
                2,
                30,
                List.of(
                        step("read-design", "KnowledgeTool", "design", "采用 Redis"),
                        step("read-runbook", "KnowledgeTool", "runbook", "缓存故障先检查命中率")
                )
        );

        WorkflowExecutionResult result = executor.execute(
                "user-1",
                "workflow-agent-1",
                "session-1",
                plan,
                List.of("KnowledgeTool"),
                step -> {
                    throw new AssertionError("Harness 拒绝后不得进入业务执行器");
                }
        );

        assertThat(result.completed()).isFalse();
        assertThat(result.steps()).singleElement().satisfies(step -> {
            assertThat(step.stepId()).isEqualTo("read-design");
            assertThat(step.status()).isEqualTo(WorkflowStepExecutionStatus.BLOCKED);
        });
        assertThat(auditStore.getBySession("session-1"))
                .singleElement()
                .satisfies(record -> assertThat(record.getOutcome().name()).isEqualTo("CIRCUIT_OPEN"));
    }

    @Test
    void shouldRejectAnInvalidPlanBeforeCreatingAnyHarnessExecution() {
        InMemoryAuditStore auditStore = new InMemoryAuditStore(harnessProperties());
        WorkflowPlanExecutor executor = newExecutor(auditStore, new InMemoryCircuitBreakerRegistry(harnessProperties()));
        WorkflowPlan invalidPlan = new WorkflowPlan(
                1,
                30,
                List.of(new WorkflowStep("read-design", "KnowledgeTool", "design", "采用 Redis", List.of()))
        );

        WorkflowExecutionResult result = executor.execute(
                "user-1",
                "workflow-agent-1",
                "session-1",
                invalidPlan,
                List.of("KnowledgeTool"),
                step -> {
                    throw new AssertionError("无效计划不得进入业务执行器");
                }
        );

        assertThat(result.completed()).isFalse();
        assertThat(result.steps()).isEmpty();
        assertThat(result.violations()).contains("步骤 read-design 缺少证据");
        assertThat(auditStore.getBySession("session-1")).isEmpty();
    }

    @Test
    void shouldStopWorkflowAndRecordErrorWhenAnAllowedStepFails() {
        InMemoryAuditStore auditStore = new InMemoryAuditStore(harnessProperties());
        WorkflowPlanExecutor executor = newExecutor(auditStore, new InMemoryCircuitBreakerRegistry(harnessProperties()));
        WorkflowPlan plan = new WorkflowPlan(
                2,
                30,
                List.of(
                        step("read-design", "KnowledgeTool", "design", "采用 Redis"),
                        step("read-runbook", "KnowledgeTool", "runbook", "缓存故障先检查命中率")
                )
        );

        WorkflowExecutionResult result = executor.execute(
                "user-1",
                "workflow-agent-1",
                "session-1",
                plan,
                List.of("KnowledgeTool"),
                step -> {
                    if (step.id().equals("read-design")) {
                        throw new IllegalStateException("受控失败");
                    }
                    throw new AssertionError("失败步骤后不得继续执行");
                }
        );

        assertThat(result.completed()).isFalse();
        assertThat(result.steps()).singleElement().satisfies(step -> {
            assertThat(step.stepId()).isEqualTo("read-design");
            assertThat(step.status()).isEqualTo(WorkflowStepExecutionStatus.FAILED);
        });
        assertThat(auditStore.getBySession("session-1"))
                .singleElement()
                .satisfies(record -> assertThat(record.getOutcome().name()).isEqualTo("ERROR"));
    }

    @Test
    void shouldStopWorkflowBeforeBusinessExecutionWhenHarnessApprovalExpires() {
        HarnessProperties properties = harnessProperties();
        properties.getHumanApproval().setEnabled(true);
        properties.getHumanApproval().setTools(List.of("KnowledgeTool"));
        properties.getHumanApproval().setTimeoutSeconds(0);
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        InMemoryCircuitBreakerRegistry circuitRegistry = new InMemoryCircuitBreakerRegistry(properties);
        WorkflowPlanExecutor executor = newExecutor(auditStore, circuitRegistry, properties, true);
        WorkflowPlan plan = new WorkflowPlan(
                1,
                30,
                List.of(step("read-design", "KnowledgeTool", "design", "采用 Redis"))
        );

        WorkflowExecutionResult result = executor.execute(
                "user-1",
                "workflow-agent-1",
                "session-1",
                plan,
                List.of("KnowledgeTool"),
                step -> {
                    throw new AssertionError("审批过期后不得进入业务执行器");
                }
        );

        assertThat(result.completed()).isFalse();
        assertThat(result.steps()).singleElement().satisfies(step -> {
            assertThat(step.status()).isEqualTo(WorkflowStepExecutionStatus.BLOCKED);
            assertThat(step.output()).contains("APPROVAL_EXPIRED");
        });
        assertThat(auditStore.getBySession("session-1"))
                .singleElement()
                .satisfies(record -> assertThat(record.getOutcome().name()).isEqualTo("EXPIRED"));
    }

    @Test
    void shouldRejectAWhitespacePaddedToolNameBeforeItCanBypassHarnessPolicy() {
        InMemoryAuditStore auditStore = new InMemoryAuditStore(harnessProperties());
        WorkflowPlanExecutor executor = newExecutor(auditStore, new InMemoryCircuitBreakerRegistry(harnessProperties()));
        WorkflowPlan plan = new WorkflowPlan(
                1,
                30,
                List.of(step("read-design", " KnowledgeTool ", "design", "采用 Redis"))
        );

        WorkflowExecutionResult result = executor.execute(
                "user-1",
                "workflow-agent-1",
                "session-1",
                plan,
                List.of("KnowledgeTool"),
                step -> {
                    throw new AssertionError("工具名未规范化时不得进入业务执行器");
                }
        );

        assertThat(result.completed()).isFalse();
        assertThat(result.steps()).isEmpty();
        assertThat(result.violations()).contains("工作流步骤工具名称不能包含首尾空白：KnowledgeTool");
        assertThat(auditStore.getBySession("session-1")).isEmpty();
    }

    private WorkflowPlanExecutor newExecutor(
            InMemoryAuditStore auditStore,
            InMemoryCircuitBreakerRegistry circuitRegistry
    ) {
        return newExecutor(auditStore, circuitRegistry, harnessProperties());
    }

    private WorkflowPlanExecutor newExecutor(
            InMemoryAuditStore auditStore,
            InMemoryCircuitBreakerRegistry circuitRegistry,
            HarnessProperties properties
    ) {
        return newExecutor(auditStore, circuitRegistry, properties, false);
    }

    private WorkflowPlanExecutor newExecutor(
            InMemoryAuditStore auditStore,
            InMemoryCircuitBreakerRegistry circuitRegistry,
            HarnessProperties properties,
            boolean approvalEnabled
    ) {
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        List<com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new CircuitBreakerInterceptor(properties, circuitRegistry));
        if (approvalEnabled) {
            interceptors.add(new HumanApprovalInterceptor(properties, approvalStore));
        }
        interceptors.add(new AuditTrailInterceptor(properties, auditStore));
        HarnessInterceptorChain interceptorChain = new HarnessInterceptorChain(interceptors);
        HarnessRunner harnessRunner = new HarnessRunner(properties, interceptorChain, approvalStore, auditStore);
        return new WorkflowPlanExecutor(new WorkflowPlanVerifier(), harnessRunner, interceptorChain, new ObjectMapper());
    }

    private WorkflowStep step(String id, String toolName, String factKey, String claim) {
        return new WorkflowStep(
                id,
                toolName,
                factKey,
                claim,
                List.of(new WorkflowEvidence("chunk-" + id, factKey, claim))
        );
    }

    private HarnessProperties harnessProperties() {
        HarnessProperties properties = new HarnessProperties();
        properties.getAudit().setEnabled(true);
        properties.getAudit().setMaxRecordsPerSession(100);
        return properties;
    }
}
