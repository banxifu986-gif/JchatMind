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
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        AtomicReference<String> observedSourceId = new AtomicReference<>();
        HarnessInterceptor sourceCapture = new HarnessInterceptor() {
            @Override
            public void beforeExecution(
                    com.kama.jchatmind.agent.harness.HarnessContext context,
                    com.kama.jchatmind.agent.harness.HarnessResult result
            ) {
                observedSourceId.set(context.getWebhookSourceId());
            }

            @Override
            public void afterExecution(com.kama.jchatmind.agent.harness.HarnessContext context, String toolResult) {
            }

            @Override
            public void onError(com.kama.jchatmind.agent.harness.HarnessContext context, Exception exception) {
            }

            @Override
            public int getOrder() {
                return 1;
            }
        };
        HarnessInterceptorChain chain = new HarnessInterceptorChain(List.of(sourceCapture));
        WorkflowPlanExecutor executor = new WorkflowPlanExecutor(
                new WorkflowPlanVerifier(),
                new HarnessRunner(properties, chain, approvalStore, auditStore),
                chain,
                new ObjectMapper(),
                Runnable::run
        );

        WorkflowExecutionResult result = executor.executeForWebhookSource(
                "7",
                "agent-1",
                "session-1",
                "build-system",
                new WorkflowPlan(1, 10, List.of(step("step-1", "KnowledgeTool", "fact", "claim"))),
                List.of("KnowledgeTool"),
                step -> "evidence"
        );

        assertThat(result.completed()).isTrue();
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
        assertThat(auditStore.getBySession("session-rejected")).isEmpty();
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
        if (approvalEnabled) {
            interceptors.add(new HumanApprovalInterceptor(
                    properties, approvalStore, mock(WebhookApprovalOutboxService.class)
            ));
        }
        interceptors.add(new AuditTrailInterceptor(properties, auditStore));
        HarnessInterceptorChain interceptorChain = new HarnessInterceptorChain(interceptors);
        HarnessRunner harnessRunner = new HarnessRunner(properties, interceptorChain, approvalStore, auditStore);
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
