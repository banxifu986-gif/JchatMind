package com.kama.jchatmind.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class RagRegressionCandidateReadinessEvaluator {

    private static final java.util.Set<String> RUNTIME_MAPPABLE_SOURCE_DOCUMENTS =
            java.util.Set.of("interview-qa", "sql-tuning");

    RagRegressionCandidateReadinessReport evaluate(RagRegressionCandidateDataset dataset) {
        return evaluate(dataset, new Thresholds(40, 1, 1, true));
    }

    RagRegressionCandidateReadinessReport evaluate(RagRegressionCandidateDataset dataset, Thresholds thresholds) {
        List<RagRegressionCandidateCase> cases = dataset.cases();
        List<RagRegressionCandidateCase> eligibleCases = cases.stream()
                .filter(item -> "approved".equals(item.reviewStatus()))
                .toList();
        List<RagRegressionCandidateCase> runtimeEligibleCases = eligibleCases.stream()
                .filter(this::isRuntimeMappable)
                .toList();
        int abstentionCases = (int) eligibleCases.stream().filter(item -> Boolean.TRUE.equals(item.shouldAbstain())).count();
        int multiTurnCases = (int) eligibleCases.stream().filter(item -> !item.conversation().isEmpty()).count();
        int crossDocumentCases = (int) eligibleCases.stream().filter(item -> !item.additionalGoldLogicalChunkIds().isEmpty()).count();
        int candidateCases = (int) cases.stream().filter(item -> "candidate".equals(item.reviewStatus())).count();
        int approvedCases = (int) cases.stream().filter(item -> "approved".equals(item.reviewStatus())).count();
        int rejectedCases = (int) cases.stream().filter(item -> "rejected".equals(item.reviewStatus())).count();
        int uniqueRetrievalGoldChunks = (int) eligibleCases.stream()
                .flatMap(item -> item.retrievalGoldLogicalChunkIds().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .size();
        int runtimeUniqueRetrievalGoldChunks = (int) runtimeEligibleCases.stream()
                .flatMap(item -> item.retrievalGoldLogicalChunkIds().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .size();
        Map<String, Integer> queryTypeCounts = new LinkedHashMap<>();
        for (RagRegressionCandidateCase item : cases) {
            queryTypeCounts.merge(item.queryType(), 1, Integer::sum);
        }
        List<String> blockers = new ArrayList<>();
        if (eligibleCases.size() < thresholds.minimumCases()) {
            blockers.add("insufficient_approved_case_count");
        }
        if (runtimeEligibleCases.size() < thresholds.minimumCases()) {
            blockers.add("insufficient_runtime_eligible_case_count");
        }
        if (multiTurnCases < thresholds.minimumMultiTurnCases()) {
            blockers.add("missing_multi_turn_coverage");
        }
        if (crossDocumentCases < thresholds.minimumCrossDocumentCases()) {
            blockers.add("missing_cross_document_coverage");
        }
        if (candidateCases > 0) {
            blockers.add("pending_human_review");
        }
        if (thresholds.requireRuntimeUuidMapping()) {
            blockers.add("runtime_uuid_mapping_not_completed");
        }
        return new RagRegressionCandidateReadinessReport(
                dataset.datasetId(), cases.size(), eligibleCases.size(), runtimeEligibleCases.size(),
                uniqueRetrievalGoldChunks, runtimeUniqueRetrievalGoldChunks, abstentionCases,
                multiTurnCases, crossDocumentCases, candidateCases, approvedCases, rejectedCases,
                Map.copyOf(queryTypeCounts), List.copyOf(blockers)
        );
    }

    private boolean isRuntimeMappable(RagRegressionCandidateCase item) {
        return RUNTIME_MAPPABLE_SOURCE_DOCUMENTS.contains(item.sourceDocumentLogicalId())
                && item.additionalGoldLogicalChunkIds().stream()
                .map(logicalChunkId -> logicalChunkId.substring(0, logicalChunkId.indexOf('#')))
                .allMatch(RUNTIME_MAPPABLE_SOURCE_DOCUMENTS::contains);
    }

    record Thresholds(
            int minimumCases,
            int minimumMultiTurnCases,
            int minimumCrossDocumentCases,
            boolean requireRuntimeUuidMapping
    ) {
    }
}
