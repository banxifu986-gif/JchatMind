package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentMultimodalAnswerGenerationEvaluator {

    private static final String SYSTEM_PROMPT = """
            Answer only from retrieved context that directly answers the question. If no context directly answers it,
            clearly state that the evidence is insufficient and do not cite anything. For answerable questions, cite
            the minimum number of sources that directly support the answer; do not cite related or duplicate sources.
            If the question explicitly names an asset type, use only sources of that type. Cite each factual claim with
            the exact `引用: <runtime chunk UUID>` marker shown in its context block. Never invent or alter a citation ID.
            """;

    private final ChatClient generatorClient;
    private final String generatorModel;

    G2CurrentMultimodalAnswerGenerationEvaluator(ChatClient generatorClient, String generatorModel) {
        this.generatorClient = generatorClient;
        this.generatorModel = StringUtils.hasText(generatorModel) ? generatorModel.trim() : null;
    }

    Report generate(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan,
            Map<String, List<RagRetrievalResult>> resultsByCaseId
    ) {
        Map<String, String> logicalChunkIdByRuntimeUuid = validateInput(profile, runtimePlan, resultsByCaseId);
        Map<String, String> assetTypeByLogicalChunkId = assetTypeByLogicalChunkId(profile);
        if (generatorClient != null && generatorModel == null) {
            throw new IllegalArgumentException("multimodal answer generator requires a model name");
        }

        Map<String, String> answers = new LinkedHashMap<>();
        Map<String, Integer> reasonCounts = new LinkedHashMap<>();
        List<CaseResult> caseResults = new ArrayList<>(profile.cases().size());
        List<Long> latencies = new ArrayList<>(profile.cases().size());
        int generatedCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
        int callCount = 0;

        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            String caseId = candidateCase.caseId();
            if (generatorClient == null) {
                answers.put(caseId, "");
                caseResults.add(new CaseResult(caseId, "skipped", "generator_disabled", 0L, null, null));
                reasonCounts.merge("generator_disabled", 1, Integer::sum);
                skippedCount++;
                continue;
            }

            String context = contextFor(
                    resultsByCaseId.get(caseId),
                    logicalChunkIdByRuntimeUuid.keySet(),
                    logicalChunkIdByRuntimeUuid,
                    assetTypeByLogicalChunkId,
                    profile.assetImportPlan().knowledgeBaseId()
            );
            long startedAt = System.nanoTime();
            callCount++;
            try {
                String answer = generatorClient.prompt()
                        .system(SYSTEM_PROMPT)
                        .user(buildPrompt(candidateCase.query(), context))
                        .call()
                        .content();
                long latencyMs = elapsedMillis(startedAt);
                latencies.add(latencyMs);
                if (!StringUtils.hasText(answer)) {
                    answers.put(caseId, "");
                    caseResults.add(new CaseResult(caseId, "failed", "empty_answer", latencyMs, null, null));
                    reasonCounts.merge("empty_answer", 1, Integer::sum);
                    failedCount++;
                    continue;
                }
                answers.put(caseId, answer);
                caseResults.add(new CaseResult(caseId, "generated", null, latencyMs, null, null));
                generatedCount++;
            } catch (Exception exception) {
                long latencyMs = elapsedMillis(startedAt);
                latencies.add(latencyMs);
                answers.put(caseId, "");
                caseResults.add(new CaseResult(
                        caseId,
                        "failed",
                        "generation_error",
                        latencyMs,
                        deepestCauseType(exception),
                        httpStatus(exception)
                ));
                reasonCounts.merge("generation_error", 1, Integer::sum);
                failedCount++;
            }
        }

        String status = generatorClient == null ? "disabled"
                : generatedCount == profile.cases().size() ? "complete"
                : generatedCount > 0 ? "partial" : "failed";
        return new Report(
                profile.datasetId(),
                generatorModel,
                status,
                profile.cases().size(),
                generatedCount,
                failedCount,
                skippedCount,
                callCount,
                percentile95(latencies),
                reasonCounts,
                answers,
                caseResults,
                true,
                false
        );
    }

    private Map<String, String> validateInput(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan,
            Map<String, List<RagRetrievalResult>> resultsByCaseId
    ) {
        if (profile == null || runtimePlan == null || !"g2-current-multimodal-v1".equals(profile.datasetId())
                || !"candidate".equals(profile.status()) || profile.releaseEligible()
                || profile.cases() == null || profile.cases().isEmpty()
                || profile.assetImportPlan() == null || !runtimePlan.candidateOnly()
                || !runtimePlan.assetImportPlan().candidateOnly()
                || !profile.assetImportPlan().equals(runtimePlan.assetImportPlan())) {
            throw new IllegalArgumentException("multimodal answer generation requires a candidate-only runtime plan");
        }
        Set<String> caseIds = new HashSet<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            if (candidateCase == null || !StringUtils.hasText(candidateCase.caseId())
                    || !StringUtils.hasText(candidateCase.query()) || !caseIds.add(candidateCase.caseId())) {
                throw new IllegalArgumentException("multimodal answer generation case is invalid or duplicated");
            }
        }
        if (resultsByCaseId == null || !resultsByCaseId.keySet().equals(caseIds)) {
            throw new IllegalStateException("multimodal retrieval results must exactly cover candidate cases");
        }

        Map<String, G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow> rowsByLogicalId = new HashMap<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow row
                : profile.assetImportPlan().chunkRows()) {
            rowsByLogicalId.put(row.logicalChunkId(), row);
        }
        if (runtimePlan.chunks() == null || runtimePlan.chunks().size() != rowsByLogicalId.size()) {
            throw new IllegalArgumentException("multimodal answer generation runtime chunk coverage is invalid");
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
                throw new IllegalArgumentException("multimodal answer generation runtime mapping is invalid");
            }
        }
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            List<RagRetrievalResult> results = resultsByCaseId.get(candidateCase.caseId());
            if (results == null || results.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalStateException("multimodal retrieval list is null or contains null: "
                        + candidateCase.caseId());
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
            Map<String, String> logicalChunkIdByRuntimeUuid,
            Map<String, String> assetTypeByLogicalChunkId,
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
            String logicalChunkId = logicalChunkIdByRuntimeUuid.get(result.getChunkId());
            String assetType = assetTypeByLogicalChunkId.get(logicalChunkId);
            if (!StringUtils.hasText(assetType)) {
                throw new IllegalStateException("multimodal citation context asset type is missing");
            }
            context.append("Asset type: ").append(assetType)
                    .append("\n引用: ").append(result.getChunkId())
                    .append('\n').append(result.getContent());
        }
        return context.toString();
    }

    private Map<String, String> assetTypeByLogicalChunkId(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile
    ) {
        Map<String, String> assetTypes = new HashMap<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow chunk
                : profile.assetImportPlan().chunkRows()) {
            Object assetValue = chunk.metadata().get("asset");
            if (assetValue instanceof Map<?, ?> asset
                    && asset.get("type") instanceof String assetType
                    && StringUtils.hasText(assetType)) {
                assetTypes.put(chunk.logicalChunkId(), assetType);
            }
        }
        return assetTypes;
    }

    private String buildPrompt(String query, String context) {
        return "Query:\n" + query + "\n\nRetrieved context:\n" + context
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

    private String deepestCauseType(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; depth < 16 && current.getCause() != null && current.getCause() != current; depth++) {
            current = current.getCause();
        }
        return current.getClass().getName();
    }

    private Integer httpStatus(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; depth < 16 && current != null; depth++, current = current.getCause()) {
            if (current instanceof RestClientResponseException responseException) {
                return responseException.getStatusCode().value();
            }
            if (current instanceof WebClientResponseException responseException) {
                return responseException.getStatusCode().value();
            }
        }
        return null;
    }

    record CaseResult(
            String caseId,
            String status,
            String reason,
            long generationLatencyMs,
            String failureType,
            Integer httpStatus
    ) {
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
            List<CaseResult> cases,
            boolean candidateOnly,
            boolean releaseEligible
    ) {
        Report {
            reasonCounts = Map.copyOf(reasonCounts);
            finalAnswersByCaseId = Map.copyOf(finalAnswersByCaseId);
            cases = List.copyOf(cases);
        }
    }
}
