package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class RagRegressionCandidateRuntimeEvaluationRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    RagRegressionCandidateRuntimeEvaluationReport evaluateAndWrite(
            RagRegressionCandidateDataset dataset,
            Path mappingPath,
            Path replayPath,
            Path reportPath
    ) throws IOException {
        RagRegressionCandidateChunkUuidMapping mapping = OBJECT_MAPPER.readValue(
                Files.readString(mappingPath), RagRegressionCandidateChunkUuidMapping.class
        );
        Map<String, RagRegressionCandidateRuntimeReplay> replays = loadReplays(replayPath);
        validateReplayCaseIds(dataset, replays.keySet());
        return new RagRegressionCandidateRuntimeEvaluator().evaluateAndWrite(dataset, mapping, replays, reportPath);
    }

    private Map<String, RagRegressionCandidateRuntimeReplay> loadReplays(Path replayPath) throws IOException {
        Map<String, RagRegressionCandidateRuntimeReplay> replays = new LinkedHashMap<>();
        for (String line : Files.readAllLines(replayPath, StandardCharsets.UTF_8)) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            RagRegressionCandidateRuntimeReplay replay = OBJECT_MAPPER.readValue(line, RagRegressionCandidateRuntimeReplay.class);
            if (!StringUtils.hasText(replay.caseId()) || replay.topRuntimeChunkUuids() == null
                    || replays.putIfAbsent(replay.caseId(), replay) != null) {
                throw new IllegalStateException("运行期 replay caseId 无效或重复: " + replay.caseId());
            }
        }
        return Map.copyOf(replays);
    }

    private void validateReplayCaseIds(RagRegressionCandidateDataset dataset, Set<String> replayCaseIds) {
        Set<String> expectedCaseIds = new LinkedHashSet<>();
        for (RagRegressionCandidateCase item : dataset.cases()) {
            if ("approved".equals(item.reviewStatus()) && hasOnlyMappedRuntimeSources(item)) {
                expectedCaseIds.add(item.caseId());
            }
        }
        if (!expectedCaseIds.equals(replayCaseIds)) {
            throw new IllegalStateException("运行期 replay case 集合必须精确覆盖全部可运行候选");
        }
    }

    private boolean hasOnlyMappedRuntimeSources(RagRegressionCandidateCase item) {
        Set<String> runtimeSources = Set.of("interview-qa", "sql-tuning");
        return runtimeSources.contains(item.sourceDocumentLogicalId())
                && item.additionalGoldLogicalChunkIds().stream()
                .map(logicalChunkId -> logicalChunkId.substring(0, logicalChunkId.indexOf('#')))
                .allMatch(runtimeSources::contains);
    }
}
