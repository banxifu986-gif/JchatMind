package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class RagRegressionCandidateRuntimeEvaluator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    RagRegressionCandidateRuntimeEvaluationReport evaluate(
            RagRegressionCandidateDataset dataset,
            RagRegressionCandidateChunkUuidMapping mapping,
            Map<String, RagRegressionCandidateRuntimeReplay> replays
    ) {
        if (!"read_only".equals(mapping.executionStatus())) {
            throw new IllegalStateException("运行期 UUID 映射不是只读执行结果");
        }
        if (!dataset.sourceKnowledgeBaseId().equals(mapping.knowledgeBaseId())) {
            throw new IllegalStateException("运行期 UUID 映射知识库与候选集不一致");
        }
        Map<String, String> runtimeUuidByLogicalChunkId = mappedUuidByLogicalChunkId(mapping);
        List<RagRegressionCandidateCase> eligibleCases = dataset.cases().stream()
                .filter(item -> "approved".equals(item.reviewStatus()))
                .filter(this::hasOnlyMappedRuntimeSources)
                .toList();
        for (RagRegressionCandidateCase item : eligibleCases) {
            if (!replays.containsKey(item.caseId())) {
                throw new IllegalStateException("缺少运行期 replay case: " + item.caseId());
            }
            if (Boolean.FALSE.equals(item.shouldAbstain())) {
                for (String logicalChunkId : item.retrievalGoldLogicalChunkIds()) {
                    if (!runtimeUuidByLogicalChunkId.containsKey(logicalChunkId)) {
                        throw new IllegalStateException("缺少唯一运行期 UUID 映射: " + logicalChunkId);
                    }
                }
            }
        }

        List<RagRegressionCandidateCase> answerableCases = eligibleCases.stream()
                .filter(item -> Boolean.FALSE.equals(item.shouldAbstain()))
                .toList();
        List<RagRegressionCandidateCase> abstentionCases = eligibleCases.stream()
                .filter(item -> Boolean.TRUE.equals(item.shouldAbstain()))
                .toList();

        return new RagRegressionCandidateRuntimeEvaluationReport(
                dataset.datasetId(),
                "runtime_replay",
                eligibleCases.size(),
                answerableCases.size(),
                abstentionCases.size(),
                answerableCases.stream().mapToDouble(item -> hitAt(replays.get(item.caseId()), goldRuntimeUuids(item, runtimeUuidByLogicalChunkId), 5)).average().orElse(0D),
                answerableCases.stream().mapToDouble(item -> reciprocalRank(replays.get(item.caseId()), goldRuntimeUuids(item, runtimeUuidByLogicalChunkId), 3)).average().orElse(0D),
                answerableCases.stream().mapToDouble(item -> RagAsMetrics.contextPrecision(
                        replays.get(item.caseId()).topRuntimeChunkUuids().stream().limit(5).toList(),
                        goldRuntimeUuids(item, runtimeUuidByLogicalChunkId)
                )).average().orElse(0D),
                answerableCases.stream().mapToDouble(item -> RagAsMetrics.contextRecall(
                        replays.get(item.caseId()).topRuntimeChunkUuids().stream().limit(5).toList(),
                        goldRuntimeUuids(item, runtimeUuidByLogicalChunkId)
                )).average().orElse(0D),
                abstentionCases.isEmpty()
                        ? 0D
                        : abstentionCases.stream().filter(item -> replays.get(item.caseId()).abstained()).count()
                                / (double) abstentionCases.size()
        );
    }

    RagRegressionCandidateRuntimeEvaluationReport evaluateAndWrite(
            RagRegressionCandidateDataset dataset,
            RagRegressionCandidateChunkUuidMapping mapping,
            Map<String, RagRegressionCandidateRuntimeReplay> replays,
            Path reportPath
    ) throws IOException {
        RagRegressionCandidateRuntimeEvaluationReport report = evaluate(dataset, mapping, replays);
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        return report;
    }

    private Map<String, String> mappedUuidByLogicalChunkId(RagRegressionCandidateChunkUuidMapping mapping) {
        return mapping.items().stream()
                .filter(item -> "mapped".equals(item.status()) && item.runtimeChunkUuids().size() == 1)
                .collect(Collectors.toMap(
                        RagRegressionCandidateChunkUuidMapping.Item::logicalChunkId,
                        item -> item.runtimeChunkUuids().get(0),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private boolean hasOnlyMappedRuntimeSources(RagRegressionCandidateCase item) {
        return Set.of("interview-qa", "sql-tuning").contains(item.sourceDocumentLogicalId())
                && item.additionalGoldLogicalChunkIds().stream()
                .map(logicalChunkId -> logicalChunkId.substring(0, logicalChunkId.indexOf('#')))
                .allMatch(sourceDocumentLogicalId -> Set.of("interview-qa", "sql-tuning").contains(sourceDocumentLogicalId));
    }

    private Set<String> goldRuntimeUuids(
            RagRegressionCandidateCase item,
            Map<String, String> runtimeUuidByLogicalChunkId
    ) {
        return item.retrievalGoldLogicalChunkIds().stream()
                .map(runtimeUuidByLogicalChunkId::get)
                .collect(Collectors.toSet());
    }

    private double hitAt(RagRegressionCandidateRuntimeReplay replay, Set<String> goldRuntimeUuids, int k) {
        return replay.topRuntimeChunkUuids().stream().limit(k).anyMatch(goldRuntimeUuids::contains) ? 1D : 0D;
    }

    private double reciprocalRank(RagRegressionCandidateRuntimeReplay replay, Set<String> goldRuntimeUuids, int k) {
        for (int index = 0; index < Math.min(k, replay.topRuntimeChunkUuids().size()); index++) {
            if (goldRuntimeUuids.contains(replay.topRuntimeChunkUuids().get(index))) {
                return 1D / (index + 1);
            }
        }
        return 0D;
    }
}
