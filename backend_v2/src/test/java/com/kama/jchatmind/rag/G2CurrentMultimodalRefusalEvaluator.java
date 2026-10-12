package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentMultimodalRefusalEvaluator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_CONTEXT_CHARACTERS = 12_000;
    private static final String SYSTEM_PROMPT = """
            The case is labelled unanswerable from the supplied context. Judge whether the final answer
            clearly abstains and avoids asserting unsupported facts. Do not use outside knowledge.
            Return exactly one JSON object: {"correctAbstention": true or false}.
            """;

    private final ChatClient judgeClient;
    private final String judgeModel;

    G2CurrentMultimodalRefusalEvaluator(ChatClient judgeClient, String judgeModel) {
        this.judgeClient = judgeClient;
        this.judgeModel = StringUtils.hasText(judgeModel) ? judgeModel.trim() : null;
    }

    Report evaluate(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan,
            Map<String, List<RagRetrievalResult>> resultsByCaseId,
            Map<String, String> answersByCaseId
    ) {
        Map<String, String> logicalChunkIdByRuntimeUuid = validateInput(
                profile, runtimePlan, resultsByCaseId, answersByCaseId
        );
        if (judgeClient != null && judgeModel == null) {
            throw new IllegalArgumentException("multimodal refusal judge requires a model name");
        }

        List<CaseScore> scores = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        Map<String, Integer> skipReasons = new LinkedHashMap<>();
        int noAnswerCount = 0;
        int evaluatedCount = 0;
        int correctCount = 0;
        int judgeCallCount = 0;
        String knowledgeBaseId = profile.assetImportPlan().knowledgeBaseId();

        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            if (!candidateCase.shouldAbstain()) {
                continue;
            }
            noAnswerCount++;
            String context = contextFor(
                    resultsByCaseId.get(candidateCase.caseId()),
                    logicalChunkIdByRuntimeUuid.keySet(),
                    knowledgeBaseId
            );
            String answer = answersByCaseId.get(candidateCase.caseId());
            String skipReason = judgeClient == null ? "judge_disabled"
                    : !StringUtils.hasText(context) ? "empty_context"
                    : !StringUtils.hasText(answer) ? "empty_answer" : null;
            if (skipReason != null) {
                addSkipped(scores, skipReasons, candidateCase.caseId(), skipReason, false, 0L);
                continue;
            }

            boolean contextTruncated = context.length() > MAX_CONTEXT_CHARACTERS;
            String boundedContext = contextTruncated ? context.substring(0, MAX_CONTEXT_CHARACTERS) : context;
            long startedAt = System.nanoTime();
            judgeCallCount++;
            try {
                String response = judgeClient.prompt()
                        .system(SYSTEM_PROMPT)
                        .user(buildPrompt(candidateCase.query(), boundedContext, answer))
                        .call()
                        .content();
                long latencyMs = elapsedMillis(startedAt);
                latencies.add(latencyMs);
                boolean correctAbstention;
                try {
                    correctAbstention = parseDecision(response);
                } catch (IllegalArgumentException | JsonProcessingException exception) {
                    addSkipped(scores, skipReasons, candidateCase.caseId(), "invalid_judge_response",
                            contextTruncated, latencyMs);
                    continue;
                }
                scores.add(new CaseScore(candidateCase.caseId(), true, null, correctAbstention,
                        contextTruncated, latencyMs));
                evaluatedCount++;
                if (correctAbstention) {
                    correctCount++;
                }
            } catch (Exception exception) {
                long latencyMs = elapsedMillis(startedAt);
                latencies.add(latencyMs);
                addSkipped(scores, skipReasons, candidateCase.caseId(), "judge_error", contextTruncated, latencyMs);
            }
        }

        String status = judgeClient == null ? "disabled"
                : evaluatedCount == noAnswerCount ? "complete"
                : evaluatedCount == 0 ? "skipped" : "partial";
        return new Report(
                profile.datasetId(),
                judgeModel,
                status,
                noAnswerCount,
                evaluatedCount,
                noAnswerCount - evaluatedCount,
                correctCount,
                evaluatedCount == 0 ? 0D : correctCount / (double) evaluatedCount,
                judgeCallCount,
                percentile95(latencies),
                Map.copyOf(skipReasons),
                List.copyOf(scores),
                true,
                false,
                "not_configured"
        );
    }

    private Map<String, String> validateInput(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan,
            Map<String, List<RagRetrievalResult>> resultsByCaseId,
            Map<String, String> answersByCaseId
    ) {
        if (profile == null || runtimePlan == null || !"g2-current-multimodal-v1".equals(profile.datasetId())
                || !"candidate".equals(profile.status()) || profile.releaseEligible()
                || profile.cases() == null || profile.cases().isEmpty()
                || profile.assetImportPlan() == null || !runtimePlan.candidateOnly()
                || !runtimePlan.assetImportPlan().candidateOnly()
                || !profile.assetImportPlan().equals(runtimePlan.assetImportPlan())) {
            throw new IllegalArgumentException("multimodal refusal evaluator requires a candidate-only runtime plan");
        }

        Set<String> caseIds = new HashSet<>();
        int noAnswerCount = 0;
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            if (candidateCase == null || !StringUtils.hasText(candidateCase.caseId())
                    || !StringUtils.hasText(candidateCase.query()) || !caseIds.add(candidateCase.caseId())) {
                throw new IllegalArgumentException("multimodal refusal case is invalid or duplicated");
            }
            if (candidateCase.shouldAbstain()) {
                noAnswerCount++;
                if (candidateCase.goldAsset() != null || candidateCase.goldChunkLogicalId() != null) {
                    throw new IllegalArgumentException("multimodal no-answer case must not define gold evidence");
                }
            }
        }
        if (noAnswerCount == 0) {
            throw new IllegalArgumentException("multimodal refusal profile has no no-answer case");
        }
        if (resultsByCaseId == null || answersByCaseId == null
                || !resultsByCaseId.keySet().equals(caseIds) || !answersByCaseId.keySet().equals(caseIds)) {
            throw new IllegalStateException("multimodal refusal results/answers must exactly cover candidate cases");
        }

        Map<String, G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow> rowsByLogicalId = new HashMap<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow row
                : profile.assetImportPlan().chunkRows()) {
            rowsByLogicalId.put(row.logicalChunkId(), row);
        }
        if (runtimePlan.chunks() == null || runtimePlan.chunks().size() != rowsByLogicalId.size()) {
            throw new IllegalArgumentException("multimodal refusal runtime chunk coverage is invalid");
        }
        Map<String, String> logicalChunkIdByRuntimeUuid = new LinkedHashMap<>();
        Set<String> logicalIds = new HashSet<>();
        for (G2CurrentMultimodalCandidateRuntimeBuilder.RuntimeChunk runtimeChunk : runtimePlan.chunks()) {
            if (runtimeChunk == null || runtimeChunk.chunk() == null
                    || !StringUtils.hasText(runtimeChunk.chunk().chunkId())
                    || !runtimeChunk.chunk().equals(rowsByLogicalId.get(runtimeChunk.chunk().logicalChunkId()))
                    || !logicalIds.add(runtimeChunk.chunk().logicalChunkId())
                    || logicalChunkIdByRuntimeUuid.put(runtimeChunk.chunk().chunkId(),
                    runtimeChunk.chunk().logicalChunkId()) != null) {
                throw new IllegalArgumentException("multimodal refusal runtime chunk mapping is invalid");
            }
        }

        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            List<RagRetrievalResult> results = resultsByCaseId.get(candidateCase.caseId());
            if (results == null || results.stream().anyMatch(java.util.Objects::isNull)
                    || answersByCaseId.get(candidateCase.caseId()) == null) {
                throw new IllegalStateException("multimodal refusal inputs contain null: " + candidateCase.caseId());
            }
            Set<String> resultIds = new HashSet<>();
            for (RagRetrievalResult result : results) {
                if (!StringUtils.hasText(result.getChunkId()) || !resultIds.add(result.getChunkId())) {
                    throw new IllegalArgumentException("multimodal retrieval chunk ID is missing or duplicated: "
                            + candidateCase.caseId());
                }
            }
        }
        return logicalChunkIdByRuntimeUuid;
    }

    private String contextFor(
            List<RagRetrievalResult> results,
            Set<String> runtimeChunkIds,
            String knowledgeBaseId
    ) {
        StringBuilder context = new StringBuilder();
        for (RagRetrievalResult result : results) {
            if (!knowledgeBaseId.equals(result.getKbId()) || !runtimeChunkIds.contains(result.getChunkId())
                    || !StringUtils.hasText(result.getContent())) {
                continue;
            }
            if (context.length() > 0) {
                context.append("\n\n");
            }
            context.append("引用: ").append(result.getChunkId())
                    .append('\n').append(result.getContent());
        }
        return context.toString();
    }

    private String buildPrompt(String query, String context, String answer) {
        return "问题:\n" + query + "\n\n检索上下文:\n" + context
                + "\n\n最终回答:\n" + answer
                + "\n\n该 case 应拒答；判断回答是否明确拒答且没有断言上下文不支持的事实。";
    }

    private boolean parseDecision(String response) throws JsonProcessingException {
        if (!StringUtils.hasText(response)) {
            throw new IllegalArgumentException("refusal judge response is blank");
        }
        String json = response.trim().replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "").trim();
        JsonNode root = OBJECT_MAPPER.readTree(json);
        if (!root.isObject() || root.size() != 1 || !root.path("correctAbstention").isBoolean()) {
            throw new IllegalArgumentException("refusal judge response schema is invalid");
        }
        return root.path("correctAbstention").asBoolean();
    }

    private void addSkipped(
            List<CaseScore> scores,
            Map<String, Integer> skipReasons,
            String caseId,
            String reason,
            boolean contextTruncated,
            long latencyMs
    ) {
        scores.add(new CaseScore(caseId, false, reason, null, contextTruncated, latencyMs));
        skipReasons.merge(reason, 1, Integer::sum);
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

    record CaseScore(
            String caseId,
            boolean evaluated,
            String skipReason,
            Boolean correctAbstention,
            boolean contextTruncated,
            long judgeLatencyMs
    ) {
    }

    record Report(
            String datasetId,
            String judgeModel,
            String status,
            int noAnswerCaseCount,
            int evaluatedCaseCount,
            int skippedCaseCount,
            int correctAbstentionCount,
            double abstentionAccuracy,
            int judgeCallCount,
            long judgeP95LatencyMs,
            Map<String, Integer> skipReasons,
            List<CaseScore> cases,
            boolean candidateOnly,
            boolean releaseEligible,
            String qualityGateStatus
    ) {
        Report {
            skipReasons = Map.copyOf(skipReasons);
            cases = List.copyOf(cases);
        }
    }
}
