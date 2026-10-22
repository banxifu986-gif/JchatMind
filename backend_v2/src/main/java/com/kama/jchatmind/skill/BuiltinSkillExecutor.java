package com.kama.jchatmind.skill;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class BuiltinSkillExecutor {

    private static final String TECHNICAL_DECISION_COMPARISON = "technical-decision-comparison";
    private static final String NO_EVIDENCE_REASON = "当前授权知识范围内没有足够证据，无法可靠完成技术方案对比。";

    private final BuiltinSkillRegistry skillRegistry;
    private final SkillKnowledgeToolExecutor knowledgeToolExecutor;
    private final Map<SkillDefinition, ThreadPoolExecutor> executionPools = new ConcurrentHashMap<>();

    public BuiltinSkillExecutor(
            BuiltinSkillRegistry skillRegistry,
            SkillKnowledgeToolExecutor knowledgeToolExecutor
    ) {
        this.skillRegistry = skillRegistry;
        this.knowledgeToolExecutor = knowledgeToolExecutor;
    }

    public SkillExecutionResult execute(
            String userId,
            String sessionId,
            String skillId,
            Map<String, Object> input,
            List<String> authorizedKnowledgeBaseIds
    ) {
        return execute(userId, sessionId, prepare(skillId, input, authorizedKnowledgeBaseIds));
    }

    PreparedSkillInvocation prepare(
            String skillId,
            Map<String, Object> input,
            List<String> authorizedKnowledgeBaseIds
    ) {
        return skillRegistry.prepare(skillId, input, authorizedKnowledgeBaseIds);
    }

    SkillExecutionResult execute(String userId, String sessionId, PreparedSkillInvocation invocation) {
        String skillId = invocation.definition().id();
        ThreadPoolExecutor executionPool = executionPools.computeIfAbsent(
                invocation.definition(), this::createExecutionPool
        );
        Future<Map<String, Object>> execution;
        try {
            execution = executionPool.submit(() -> switch (invocation.definition().id()) {
                case TECHNICAL_DECISION_COMPARISON -> executeTechnicalDecisionComparison(userId, sessionId, invocation);
                default -> throw new IllegalArgumentException("未实现的内置 Skill：" + skillId);
            });
        } catch (RejectedExecutionException exception) {
            return abstained(invocation, "当前 Skill 并发预算已满，请稍后重试。");
        }

        Map<String, Object> output;
        try {
            output = execution.get(invocation.definition().timeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            execution.cancel(true);
            return abstained(invocation, "Skill 执行超时，未返回未经验证的结果。");
        } catch (InterruptedException exception) {
            execution.cancel(true);
            Thread.currentThread().interrupt();
            return abstained(invocation, "Skill 执行被中断，未返回未经验证的结果。");
        } catch (ExecutionException exception) {
            return abstained(invocation, "Skill 执行失败，未返回未经验证的结果。");
        }
        return new SkillExecutionResult(Map.copyOf(output), skillRegistry.validateOutput(invocation, output));
    }

    @PreDestroy
    void shutdownExecutionPools() {
        executionPools.values().forEach(ThreadPoolExecutor::shutdownNow);
        executionPools.clear();
    }

    private ThreadPoolExecutor createExecutionPool(SkillDefinition definition) {
        if (definition.concurrencyLimit() < 1 || definition.timeoutSeconds() < 1) {
            throw new IllegalStateException("内置 Skill 执行预算必须大于零");
        }
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "builtin-skill-" + definition.id() + "-" + threadNumber.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                definition.concurrencyLimit(),
                definition.concurrencyLimit(),
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private SkillExecutionResult abstained(PreparedSkillInvocation invocation, String reason) {
        Map<String, Object> output = Map.of("abstained", true, "reason", reason);
        return new SkillExecutionResult(output, skillRegistry.validateOutput(invocation, output));
    }

    private Map<String, Object> executeTechnicalDecisionComparison(
            String userId,
            String sessionId,
            PreparedSkillInvocation invocation
    ) {
        String question = (String) invocation.input().get("question");
        SkillKnowledgeToolResult toolResult = knowledgeToolExecutor.execute(
                userId,
                sessionId,
                question,
                invocation.knowledgeBaseIds()
        );
        if (!toolResult.executed()) {
            return abstention(toolResult.reason());
        }

        List<Map<String, Object>> evidence = collectEvidence(toolResult.results(), invocation.knowledgeBaseIds());
        if (evidence.isEmpty()) {
            return abstention(NO_EVIDENCE_REASON);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("conclusion", "已检索到支持技术方案对比的 " + evidence.size() + " 条授权知识库证据。");
        output.put("evidence", List.copyOf(evidence));
        return output;
    }

    private List<Map<String, Object>> collectEvidence(
            List<RagRetrievalResult> results,
            List<String> authorizedKnowledgeBaseIds
    ) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (RagRetrievalResult result : results) {
            if (result == null
                    || !StringUtils.hasText(result.getChunkId())
                    || !StringUtils.hasText(result.getKbId())
                    || !authorizedKnowledgeBaseIds.contains(result.getKbId())) {
                continue;
            }
            evidence.add(Map.of("chunkId", result.getChunkId(), "kbId", result.getKbId()));
        }
        return evidence;
    }

    private Map<String, Object> abstention(String reason) {
        return Map.of(
                "abstained", true,
                "reason", StringUtils.hasText(reason) ? reason : NO_EVIDENCE_REASON
        );
    }
}

@FunctionalInterface
interface SkillKnowledgeToolExecutor {
    SkillKnowledgeToolResult execute(String userId, String sessionId, String question, List<String> knowledgeBaseIds);
}

record SkillKnowledgeToolResult(boolean executed, List<RagRetrievalResult> results, String reason) {

    static SkillKnowledgeToolResult executed(List<RagRetrievalResult> results) {
        return new SkillKnowledgeToolResult(true, results == null ? List.of() : List.copyOf(results), null);
    }

    static SkillKnowledgeToolResult blocked(String reason) {
        return new SkillKnowledgeToolResult(false, List.of(), reason);
    }
}

record SkillExecutionResult(Map<String, Object> output, SkillOutputValidationResult validation) {
}
