package com.kama.jchatmind.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentFixtureAnswerGenerationEvaluator {

    static final String SYSTEM_PROMPT_VERSION = "citation-minimal-v3";
    private static final String SYSTEM_PROMPT = """
            Answer only from the supplied retrieved context. Answer the exact question and every requested part;
            omit related details the question did not ask for. For a general question, state the general rule only;
            do not add conditional exceptions or edge cases unless the query explicitly asks for them. If the context
            does not support the requested answer,
            state that the evidence is insufficient and abstain without a citation marker. Every factual claim must
            have a direct supporting citation using the exact `引用: <runtime chunk UUID>` marker present in its
            supporting chunk. Use the minimum number of citation markers needed, cite each supporting chunk at most
            once, and never cite related but unnecessary context. Never invent or alter a citation ID.
            """;
    static final String SYSTEM_PROMPT_SHA256 = sha256(SYSTEM_PROMPT);

    private final ChatClient generatorClient;
    private final String generatorModel;

    G2CurrentFixtureAnswerGenerationEvaluator(ChatClient generatorClient, String generatorModel) {
        this.generatorClient = generatorClient;
        this.generatorModel = StringUtils.hasText(generatorModel) ? generatorModel.trim() : null;
    }

    Report generate(G2CurrentFixtureProfile profile, Map<String, String> contextsByCaseId) {
        Set<String> caseIds = validateInput(profile, contextsByCaseId);
        Map<String, String> queriesByCaseId = new LinkedHashMap<>();
        profile.cases().forEach(item -> queriesByCaseId.put(item.caseId(), item.query()));
        return generate(profile.datasetId(), queriesByCaseId, contextsByCaseId, caseIds);
    }

    Report generate(
            G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate,
            Map<String, String> contextsByCaseId
    ) {
        if (candidate == null || !"candidate".equals(candidate.status())
                || !"approved".equals(candidate.reviewStatus()) || candidate.cases() == null) {
            throw new IllegalStateException("citation candidate 只有全部通过 review 后才可生成答案");
        }
        Map<String, String> queriesByCaseId = new LinkedHashMap<>();
        for (G2CurrentFixtureCitationCandidateLoader.CandidateCase item : candidate.cases()) {
            if (item == null || !StringUtils.hasText(item.caseId()) || !StringUtils.hasText(item.query())
                    || !"approved".equals(item.reviewStatus())
                    || queriesByCaseId.put(item.caseId(), item.query()) != null) {
                throw new IllegalStateException("citation candidate case 未全部审核通过或无效");
            }
        }
        Set<String> caseIds = queriesByCaseId.keySet();
        if (caseIds.isEmpty() || contextsByCaseId == null || !contextsByCaseId.keySet().equals(caseIds)) {
            throw new IllegalStateException("answer generation contexts 必须精确覆盖 approved candidate");
        }
        return generate(candidate.datasetId(), queriesByCaseId, contextsByCaseId, caseIds);
    }

    private Report generate(
            String datasetId,
            Map<String, String> queriesByCaseId,
            Map<String, String> contextsByCaseId,
            Set<String> caseIds
    ) {
        Map<String, String> answers = new LinkedHashMap<>();
        Map<String, Integer> reasonCounts = new LinkedHashMap<>();
        List<CaseResult> results = new ArrayList<>(caseIds.size());
        List<Long> latencies = new ArrayList<>(caseIds.size());
        int generatedCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
        int callCount = 0;

        for (Map.Entry<String, String> queryEntry : queriesByCaseId.entrySet()) {
            String caseId = queryEntry.getKey();
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
                        .user(buildPrompt(queryEntry.getValue(), contextsByCaseId.get(caseId)))
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
                datasetId,
                generatorModel,
                status,
                caseIds.size(),
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

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
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
