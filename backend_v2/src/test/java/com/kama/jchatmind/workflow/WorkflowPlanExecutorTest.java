package com.kama.jchatmind.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.AsyncConfig;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.agent.harness.approval.InMemoryApprovalStore;
import com.kama.jchatmind.agent.harness.audit.InMemoryAuditStore;
import com.kama.jchatmind.agent.harness.circuit.InMemoryCircuitBreakerRegistry;
import com.kama.jchatmind.agent.harness.interceptor.AuditTrailInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.CircuitBreakerInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.HumanApprovalInterceptor;
import com.kama.jchatmind.webhook.WebhookApprovalOutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkflowPlanExecutorTest {

    @Test
    void shouldPropagateExplicitWebhookSourceIdIntoHarnessApprovalContext() {
        HarnessProperties properties = harnessProperties();
        properties.getHumanApproval().setEnabled(true);
        properties.getHumanApproval().setTools(List.of("KnowledgeTool"));
        // 审批超时置 0：enqueue 后立即过期进入 BLOCKED，测试不阻塞
        properties.getHumanApproval().setTimeoutSeconds(0);
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        // P1 后 webhookSourceId 由 Runner 直调审批拦截器传递，经 webhook outbox 出站可观测
        WebhookApprovalOutboxService outbox = mock(WebhookApprovalOutboxService.class);
        com.kama.jchatmind.agent.harness.interceptor.HumanApprovalInterceptor approvalInterceptor =
                new com.kama.jchatmind.agent.harness.interceptor.HumanApprovalInterceptor(
                        properties, approvalStore, outbox);
        HarnessInterceptorChain chain = new HarnessInterceptorChain(List.of());
        WorkflowPlanExecutor executor = new WorkflowPlanExecutor(
                new WorkflowPlanVerifier(),
                new HarnessRunner(properties, approvalInterceptor, approvalStore, auditStore),
                chain,
                new ObjectMapper(),
                Runnable::run
        );
        AtomicReference<String> observedSourceId = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            observedSourceId.set(invocation.getArgument(0));
            return null;
        }).when(outbox).enqueue(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        WorkflowExecutionResult result = executor.executeForWebhookSource(
                "7",
                "agent-1",
                "session-1",
                "build-system",
                new WorkflowPlan(1, 10, List.of(step("step-1", "KnowledgeTool", "fact", "claim"))),
                List.of("KnowledgeTool"),
                step -> "evidence"
        );

        org.mockito.Mockito.verify(outbox).enqueue(
                org.mockito.ArgumentMatchers.eq("build-system"),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("session-1"));
        assertThat(observedSourceId.get()).isEqualTo("build-system");
    }

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
    }

    /**
     * specs/003 T310 R3 竞态语义（确定性复现）：超时已触发后，running step 的晚到 FAILED
     * 记录不得改写其 TIMED_OUT 状态（超时优先，对齐 pi aborted 优先）。
     * 现实现 timedOut() 对"running step 已在列表"直接跳过合成 → FAILED 抢占 → 本测试红。
     */
    @Test
    void timedOutShouldOverrideLateArrivingFailedStatusOfRunningStep() {
        WorkflowStepExecution lateFailed =
                new WorkflowStepExecution(
                        "slow-step", WorkflowStepExecutionStatus.FAILED, "工作流工具执行失败");

        WorkflowExecutionResult result = WorkflowExecutionResult.timedOut(
                List.of(lateFailed), "slow-step");

        assertThat(result.steps()).singleElement().satisfies(step -> {
            assertThat(step.stepId()).isEqualTo("slow-step");
            assertThat(step.status()).isEqualTo(WorkflowStepExecutionStatus.TIMED_OUT);
        });
        assertThat(result.completed()).isFalse();
    }

    @Test
    void timedOutShouldSynthesizeTimedOutStepWhenRunningStepNotYetRecorded() {
        WorkflowExecutionResult result = WorkflowExecutionResult.timedOut(
                List.of(), "slow-step");

        assertThat(result.steps()).singleElement().satisfies(step -> {
            assertThat(step.stepId()).isEqualTo("slow-step");
            assertThat(step.status()).isEqualTo(WorkflowStepExecutionStatus.TIMED_OUT);
        });
    }

    @Test
    void timedOutShouldKeepFinishedStepsUntouchedAndAppendRunningStep() {
        WorkflowStepExecution succeeded =
                new WorkflowStepExecution(
                        "step-1", WorkflowStepExecutionStatus.SUCCEEDED, "done");

        WorkflowExecutionResult result = WorkflowExecutionResult.timedOut(
                List.of(succeeded), "step-2");

        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepExecutionStatus.SUCCEEDED);
        assertThat(result.steps().get(1).status()).isEqualTo(WorkflowStepExecutionStatus.TIMED_OUT);
    }

    @Test
    void shouldInterruptTimedOutStepAndStopLaterSteps() throws Exception {
        InMemoryAuditStore auditStore = new InMemoryAuditStore(harnessProperties());
        AnnotationConfigApplicationContext taskExecutorContext =
                new AnnotationConfigApplicationContext(AsyncConfig.class);
        ThreadPoolTaskExecutor workflowTaskExecutor = taskExecutorContext.getBean(
                "workflowTaskExecutor",
                ThreadPoolTaskExecutor.class
        );
        WorkflowPlanExecutor executor = newExecutor(
                auditStore,
                new InMemoryCircuitBreakerRegistry(harnessProperties()),
                harnessProperties(),
                false,
                workflowTaskExecutor
        );
        CountDownLatch firstStepStarted = new CountDownLatch(1);
        CountDownLatch firstStepInterrupted = new CountDownLatch(1);
        CountDownLatch releaseFirstStep = new CountDownLatch(1);
        CountDownLatch executionFinished = new CountDownLatch(1);
        AtomicReference<WorkflowExecutionResult> executionResult = new AtomicReference<>();
        AtomicReference<Throwable> executionFailure = new AtomicReference<>();
        List<String> executedStepIds = new ArrayList<>();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        WorkflowPlan plan = new WorkflowPlan(
                2,
                1,
                List.of(
                        step("slow-step", "KnowledgeTool", "slow", "可中断的读取"),
                        step("later-step", "KnowledgeTool", "later", "不得运行的后续读取")
                )
        );

        try {
            assertThat(workflowTaskExecutor.getCorePoolSize()).isEqualTo(2);
            assertThat(workflowTaskExecutor.getMaxPoolSize()).isEqualTo(4);
            assertThat(workflowTaskExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(20);
            caller.submit(() -> {
                try {
                    RequestContextHolder.setRequestAttributes(
                            new ServletRequestAttributes(new MockHttpServletRequest())
                    );
                    try {
                        executionResult.set(executor.execute(
                                "user-1",
                                "workflow-agent-1",
                                "session-timeout",
                                plan,
                                List.of("KnowledgeTool"),
                                step -> {
                                    executedStepIds.add(step.id());
                                    if (step.id().equals("slow-step")) {
                                        firstStepStarted.countDown();
                                        try {
                                            releaseFirstStep.await();
                                            return "completed";
                                        } catch (InterruptedException exception) {
                                            firstStepInterrupted.countDown();
                                            Thread.currentThread().interrupt();
                                            throw new IllegalStateException(exception);
                                        }
                                    }
                                    return "unexpected";
                                }
                        ));
                    } finally {
                        RequestContextHolder.resetRequestAttributes();
                    }
                } catch (Throwable exception) {
                    executionFailure.set(exception);
                } finally {
                    executionFinished.countDown();
                }
            });

            assertThat(firstStepStarted.await(2, TimeUnit.SECONDS)).isTrue();
            boolean completedWithinTimeout = executionFinished.await(1500, TimeUnit.MILLISECONDS);
            releaseFirstStep.countDown();
            assertThat(executionFinished.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(completedWithinTimeout).isTrue();
            assertThat(executionFailure.get()).isNull();
            assertThat(firstStepInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(executedStepIds).containsExactly("slow-step");
            assertThat(executionResult.get().steps()).singleElement()
                    .satisfies(result -> assertThat(result.status().name()).isEqualTo("TIMED_OUT"));
        } finally {
            releaseFirstStep.countDown();
            caller.shutdownNow();
            taskExecutorContext.close();
        }
    }

    @Test
    void shouldFailClosedWhenWorkflowExecutorRejectsPlan() {
        InMemoryAuditStore auditStore = new InMemoryAuditStore(harnessProperties());
        AtomicBoolean businessExecutorCalled = new AtomicBoolean();
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("workflow pool is full");
        };
        WorkflowPlanExecutor executor = newExecutor(
                auditStore,
                new InMemoryCircuitBreakerRegistry(harnessProperties()),
                harnessProperties(),
                false,
                rejectingExecutor
        );

        WorkflowExecutionResult result = executor.execute(
                "user-1",
                "workflow-agent-1",
                "session-rejected",
                new WorkflowPlan(1, 30, List.of(step("read-design", "KnowledgeTool", "design", "采用 Redis"))),
                List.of("KnowledgeTool"),
                step -> {
                    businessExecutorCalled.set(true);
                    return "unexpected";
                }
        );

        assertThat(result.completed()).isFalse();
        assertThat(result.steps()).isEmpty();
        assertThat(result.violations()).contains("工作流执行线程池繁忙");
        assertThat(businessExecutorCalled).isFalse();
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
        return newExecutor(auditStore, circuitRegistry, properties, approvalEnabled, Runnable::run);
    }

    private WorkflowPlanExecutor newExecutor(
            InMemoryAuditStore auditStore,
            InMemoryCircuitBreakerRegistry circuitRegistry,
            HarnessProperties properties,
            boolean approvalEnabled,
            Executor workflowTaskExecutor
    ) {
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        List<com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new CircuitBreakerInterceptor(properties, circuitRegistry));
        interceptors.add(new AuditTrailInterceptor(properties, auditStore));
        HumanApprovalInterceptor approvalInterceptor = new HumanApprovalInterceptor(
                properties, approvalStore, mock(WebhookApprovalOutboxService.class));
        HarnessInterceptorChain interceptorChain = new HarnessInterceptorChain(interceptors);
        HarnessRunner harnessRunner = new HarnessRunner(properties, approvalInterceptor, approvalStore, auditStore);
        return new WorkflowPlanExecutor(
                new WorkflowPlanVerifier(),
                harnessRunner,
                interceptorChain,
                new ObjectMapper(),
                workflowTaskExecutor
        );
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
