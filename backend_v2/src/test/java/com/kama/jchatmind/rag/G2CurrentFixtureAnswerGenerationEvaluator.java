package com.kama.jchatmind.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentFixtureAnswerGenerationEvaluator {

    private static final String SYSTEM_PROMPT = """
            Answer only from the supplied retrieved context. If it does not support an answer, clearly state that
            the evidence is insufficient. Cite factual claims with the exact `引用: <runtime chunk UUID>` marker
            present in the context. Never invent or alter a citation ID.
            """;

    private final ChatClient generatorClient;
    private final String generatorModel;

    G2CurrentFixtureAnswerGenerationEvaluator(ChatClient generatorClient, String generatorModel) {
        this.generatorClient = generatorClient;
        this.generatorModel = StringUtils.hasText(generatorModel) ? generatorModel.trim() : null;
    }

    Report generate(G2CurrentFixtureProfile profile, Map<String, String> contextsByCaseId) {
        Set<String> caseIds = validateInput(profile, contextsByCaseId);
        Map<String, String> answers = new LinkedHashMap<>();
        Map<String, Integer> reasonCounts = new LinkedHashMap<>();
        List<CaseResult> results = new ArrayList<>(caseIds.size());
        List<Long> latencies = new ArrayList<>(caseIds.size());
        int generatedCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
        int callCount = 0;

        for (G2CurrentFixtureCase item : profile.cases()) {
            String caseId = item.caseId();
            if (generatorClient == null) {
                answers.put(caseId, "");
                results.add(new CaseResult(caseId, "skipped", "generator_disabled", 0L));
                reasonCounts.merge("generator_disabled", 1, Integer::sum);
                skippedCount++;
                continue;
            }

            long startedAt = System.nanoTime();
            callCount++;
            try {
                String answer = generatorClient.prompt()
                        .system(SYSTEM_PROMPT)
                        .user(buildPrompt(item.query(), contextsByCaseId.get(caseId)))
                        .call()
                        .content();
                long latencyMs = elapsedMillis(startedAt);
                latencies.add(latencyMs);
                if (!StringUtils.hasText(answer)) {
                    answers.put(caseId, "");
                    results.add(new CaseResult(caseId, "failed", "empty_answer", latencyMs));
                    reasonCounts.merge("empty_answer", 1, Integer::sum);
                    failedCount++;
                    continue;
                }
                answers.put(caseId, answer);
                results.add(new CaseResult(caseId, "generated", null, latencyMs));
                generatedCount++;
            } catch (Exception exception) {
                long latencyMs = elapsedMillis(startedAt);
                latencies.add(latencyMs);
                answers.put(caseId, "");
                results.add(new CaseResult(caseId, "failed", "generation_error", latencyMs));
                reasonCounts.merge("generation_error", 1, Integer::sum);
                failedCount++;
            }
        }

        String status = generatorClient == null ? "disabled"
                : generatedCount == caseIds.size() ? "complete"
                : generatedCount > 0 ? "partial" : "failed";
        return new Report(
                profile.datasetId(),
                generatorModel,
                status,
                profile.caseCount(),
                generatedCount,
                failedCount,
                skippedCount,
                callCount,
                percentile95(latencies),
                reasonCounts,
                answers,
                results
        );
    }

    private Set<String> validateInput(G2CurrentFixtureProfile profile, Map<String, String> contextsByCaseId) {
        if (profile == null || !"fixture-fast-v1".equals(profile.datasetId())
                || profile.cases() == null || profile.caseCount() != profile.cases().size()) {
            throw new IllegalArgumentException("current fixture answer generation profile 无效");
        }
        Set<String> caseIds = new HashSet<>();
        for (G2CurrentFixtureCase item : profile.cases()) {
            if (item == null || !StringUtils.hasText(item.caseId()) || !StringUtils.hasText(item.query())
                    || !caseIds.add(item.caseId())) {
                throw new IllegalArgumentException("current fixture answer generation case 无效或重复");
            }
        }
        if (contextsByCaseId == null || !contextsByCaseId.keySet().equals(caseIds)) {
            throw new IllegalStateException("answer generation contexts 必须精确覆盖 current fixture");
        }
        return caseIds;
    }

    private String buildPrompt(String query, String context) {
        return "Query:\n" + query + "\n\nRetrieved context:\n"
                + (context == null ? "" : context)
                + "\n\nReturn only the final answer.";
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private long percentile95(List<Long> values) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get(Math.max(0, (int) Math.ceil(0.95D * sorted.size()) - 1));
    }

    record CaseResult(String caseId, String status, String reason, long generationLatencyMs) {
    }

    record Report(
            String datasetId,
            String generatorModel,
            String status,
            int caseCount,
            int generatedCaseCount,
            int failedCaseCount,
            int skippedCaseCount,
            int generationCallCount,
            long p95GenerationLatencyMs,
            Map<String, Integer> reasonCounts,
            Map<String, String> finalAnswersByCaseId,
            List<CaseResult> cases
    ) {
        Report {
            reasonCounts = Map.copyOf(reasonCounts);
            finalAnswersByCaseId = Map.copyOf(finalAnswersByCaseId);
            cases = List.copyOf(cases);
        }
    }
}
