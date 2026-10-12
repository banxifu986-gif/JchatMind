package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class G2CurrentMultimodalAnswerCitationEvaluator {

    private static final String CITATION_MARKER = "引用:";
    private static final String FULL_WIDTH_CITATION_MARKER = "引用：";
    private static final Pattern RUNTIME_UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b"
    );

    Report evaluate(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan,
            Map<String, List<RagRetrievalResult>> resultsByCaseId,
            Map<String, String> finalAnswersByCaseId
    ) {
        validateCandidate(profile, runtimePlan);
        Map<String, String> logicalChunkIdByRuntimeUuid = indexRuntimeChunks(profile, runtimePlan);
        Set<String> caseIds = indexCases(profile);
        validateInputs(profile, resultsByCaseId, finalAnswersByCaseId, caseIds);

        List<CaseSummary> caseSummaries = new ArrayList<>(profile.cases().size());
        int answerableCases = 0;
        int abstentionCases = 0;
        int exactCitationMatches = 0;
        int noAnswerCitationCount = 0;
        int unmappedCitationCount = 0;
        int outOfScopeCitationCount = 0;
        int malformedCitationMarkerCount = 0;
        int duplicateCitationCount = 0;
        double precisionSum = 0D;
        double recallSum = 0D;

        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            ParsedCitations parsed = parseCitations(
                    finalAnswersByCaseId.get(candidateCase.caseId()),
                    logicalChunkIdByRuntimeUuid,
                    retrievedRuntimeChunkIds(resultsByCaseId.get(candidateCase.caseId()),
                            profile.assetImportPlan().knowledgeBaseId())
            );
            int citationCount = parsed.runtimeCitationCount() + parsed.malformedMarkerCount();
            unmappedCitationCount += parsed.unmappedCitationCount();
            outOfScopeCitationCount += parsed.outOfScopeCitationCount();
            malformedCitationMarkerCount += parsed.malformedMarkerCount();
            duplicateCitationCount += parsed.duplicateCitationCount();

            if (candidateCase.shouldAbstain()) {
                abstentionCases++;
                noAnswerCitationCount += citationCount;
                caseSummaries.add(new CaseSummary(
                        candidateCase.caseId(),
                        List.of(),
                        parsed.logicalChunkIds(),
                        0D,
                        0D,
                        citationCount == 0,
                        parsed.unmappedCitationCount(),
                        parsed.outOfScopeCitationCount(),
                        parsed.malformedMarkerCount(),
                        parsed.duplicateCitationCount()
                ));
                continue;
            }

            answerableCases++;
            Set<String> expected = Set.of(candidateCase.goldChunkLogicalId());
            Set<String> cited = new LinkedHashSet<>(parsed.logicalChunkIds());
            long truePositives = parsed.evidenceBackedLogicalChunkIds().stream()
                    .filter(expected::contains)
                    .count();
            double precision = citationCount == 0 ? 0D : truePositives / (double) citationCount;
            double recall = Math.min(1D, truePositives / (double) expected.size());
            boolean exact = cited.equals(expected)
                    && citationCount == expected.size()
                    && parsed.unmappedCitationCount() == 0
                    && parsed.outOfScopeCitationCount() == 0
                    && parsed.malformedMarkerCount() == 0
                    && parsed.duplicateCitationCount() == 0;
            precisionSum += precision;
            recallSum += recall;
            if (exact) {
                exactCitationMatches++;
            }
            caseSummaries.add(new CaseSummary(
                    candidateCase.caseId(),
                    List.of(candidateCase.goldChunkLogicalId()),
                    parsed.logicalChunkIds(),
                    precision,
                    recall,
                    exact,
                    parsed.unmappedCitationCount(),
                    parsed.outOfScopeCitationCount(),
                    parsed.malformedMarkerCount(),
                    parsed.duplicateCitationCount()
            ));
        }

        double meanPrecision = answerableCases == 0 ? 0D : precisionSum / answerableCases;
        double meanRecall = answerableCases == 0 ? 0D : recallSum / answerableCases;
        double exactMatchAccuracy = answerableCases == 0 ? 0D : exactCitationMatches / (double) answerableCases;
        Gate gate = gate(meanPrecision, meanRecall, exactMatchAccuracy, noAnswerCitationCount,
                unmappedCitationCount, outOfScopeCitationCount, malformedCitationMarkerCount,
                duplicateCitationCount);
        return new Report(
                profile.datasetId(),
                profile.cases().size(),
                answerableCases,
                abstentionCases,
                meanPrecision,
                meanRecall,
                exactMatchAccuracy,
                noAnswerCitationCount,
                unmappedCitationCount,
                outOfScopeCitationCount,
                malformedCitationMarkerCount,
                duplicateCitationCount,
                List.copyOf(caseSummaries),
                gate,
                true,
                false,
                false,
                false
        );
    }

    private void validateCandidate(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan
    ) {
        if (profile == null || runtimePlan == null || !"candidate".equals(profile.status())
                || profile.releaseEligible() || profile.cases() == null || profile.cases().isEmpty()
                || profile.assetImportPlan() == null || !runtimePlan.candidateOnly()
                || !runtimePlan.assetImportPlan().candidateOnly()
                || !profile.assetImportPlan().equals(runtimePlan.assetImportPlan())) {
            throw new IllegalArgumentException("multimodal citation evaluator 仅接受 candidate-only runtime plan");
        }
    }

    private Set<String> indexCases(G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile) {
        Set<String> caseIds = new LinkedHashSet<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            if (candidateCase == null || !StringUtils.hasText(candidateCase.caseId())
                    || !caseIds.add(candidateCase.caseId())) {
                throw new IllegalArgumentException("multimodal citation evaluator caseId 无效或重复");
            }
            if (candidateCase.shouldAbstain()) {
                if (candidateCase.goldAsset() != null || candidateCase.goldChunkLogicalId() != null) {
                    throw new IllegalArgumentException("multimodal no-answer case 不应声明 gold citation");
                }
            } else if (!StringUtils.hasText(candidateCase.goldChunkLogicalId())
                    || candidateCase.goldAsset() == null) {
                throw new IllegalArgumentException("multimodal answerable case 缺少 gold citation");
            }
        }
        return caseIds;
    }

    private Map<String, String> indexRuntimeChunks(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan
    ) {
        Map<String, G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow> importRowsByLogicalId = new HashMap<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow row
                : profile.assetImportPlan().chunkRows()) {
            importRowsByLogicalId.put(row.logicalChunkId(), row);
        }
        Map<String, String> logicalChunkIdByRuntimeUuid = new LinkedHashMap<>();
        Set<String> logicalIds = new HashSet<>();
        if (runtimePlan.chunks() == null || runtimePlan.chunks().size() != importRowsByLogicalId.size()) {
            throw new IllegalArgumentException("multimodal citation runtime chunk coverage 不匹配");
        }
        for (G2CurrentMultimodalCandidateRuntimeBuilder.RuntimeChunk runtimeChunk : runtimePlan.chunks()) {
            if (runtimeChunk == null || runtimeChunk.chunk() == null
                    || !StringUtils.hasText(runtimeChunk.chunk().chunkId())
                    || !runtimeChunk.chunk().equals(importRowsByLogicalId.get(runtimeChunk.chunk().logicalChunkId()))
                    || !logicalIds.add(runtimeChunk.chunk().logicalChunkId())
                    || logicalChunkIdByRuntimeUuid.put(runtimeChunk.chunk().chunkId(),
                    runtimeChunk.chunk().logicalChunkId()) != null) {
                throw new IllegalArgumentException("multimodal citation runtime chunk mapping 无效或重复");
            }
        }
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            if (!candidateCase.shouldAbstain()
                    && !logicalChunkIdByRuntimeUuid.containsValue(candidateCase.goldChunkLogicalId())) {
                throw new IllegalStateException("multimodal citation runtime map 缺少 gold chunk: "
                        + candidateCase.caseId());
            }
        }
        return logicalChunkIdByRuntimeUuid;
    }

    private void validateInputs(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            Map<String, List<RagRetrievalResult>> resultsByCaseId,
            Map<String, String> finalAnswersByCaseId,
            Set<String> caseIds
    ) {
        if (resultsByCaseId == null || !resultsByCaseId.keySet().equals(caseIds)
                || finalAnswersByCaseId == null || !finalAnswersByCaseId.keySet().equals(caseIds)) {
            throw new IllegalStateException("multimodal citation answers/retrieval 必须精确覆盖所有 case");
        }
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            if (finalAnswersByCaseId.get(candidateCase.caseId()) == null) {
                throw new IllegalStateException("multimodal final answer 为 null: " + candidateCase.caseId());
            }
            List<RagRetrievalResult> results = resultsByCaseId.get(candidateCase.caseId());
            if (results == null || results.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalStateException("multimodal retrieval list 为空或包含 null: "
                        + candidateCase.caseId());
            }
            Set<String> retrievedIds = new HashSet<>();
            for (RagRetrievalResult result : results) {
                if (!StringUtils.hasText(result.getChunkId()) || !retrievedIds.add(result.getChunkId())) {
                    throw new IllegalArgumentException("multimodal retrieval chunk ID 缺失或重复: "
                            + candidateCase.caseId());
                }
            }
        }
    }

    private Set<String> retrievedRuntimeChunkIds(List<RagRetrievalResult> results, String knowledgeBaseId) {
        Set<String> retrievedIds = new HashSet<>();
        for (RagRetrievalResult result : results) {
            if (knowledgeBaseId.equals(result.getKbId())) {
                retrievedIds.add(result.getChunkId());
            }
        }
        return retrievedIds;
    }

    private ParsedCitations parseCitations(
            String answer,
            Map<String, String> logicalChunkIdByRuntimeUuid,
            Set<String> retrievedRuntimeChunkIds
    ) {
        List<String> logicalChunkIds = new ArrayList<>();
        List<String> evidenceBackedLogicalChunkIds = new ArrayList<>();
        Set<String> seenRuntimeIds = new HashSet<>();
        int runtimeCitationCount = 0;
        int unmappedCitationCount = 0;
        int outOfScopeCitationCount = 0;
        int malformedMarkerCount = 0;
        int duplicateCitationCount = 0;
        for (String line : answer.split("\\R")) {
            List<MarkerSpan> markers = markerSpans(line);
            for (int index = 0; index < markers.size(); index++) {
                MarkerSpan marker = markers.get(index);
                int end = index + 1 < markers.size() ? markers.get(index + 1).start() : line.length();
                Matcher uuidMatcher = RUNTIME_UUID.matcher(line.substring(marker.end(), end));
                if (!uuidMatcher.find()) {
                    malformedMarkerCount++;
                    continue;
                }
                runtimeCitationCount++;
                String runtimeUuid = uuidMatcher.group().toLowerCase(Locale.ROOT);
                if (!seenRuntimeIds.add(runtimeUuid)) {
                    duplicateCitationCount++;
                    continue;
                }
                String logicalChunkId = logicalChunkIdByRuntimeUuid.get(runtimeUuid);
                if (logicalChunkId == null) {
                    unmappedCitationCount++;
                    continue;
                }
                logicalChunkIds.add(logicalChunkId);
                if (retrievedRuntimeChunkIds.contains(runtimeUuid)) {
                    evidenceBackedLogicalChunkIds.add(logicalChunkId);
                } else {
                    outOfScopeCitationCount++;
                }
            }
        }
        return new ParsedCitations(
                List.copyOf(logicalChunkIds),
                List.copyOf(evidenceBackedLogicalChunkIds),
                runtimeCitationCount,
                unmappedCitationCount,
                outOfScopeCitationCount,
                malformedMarkerCount,
                duplicateCitationCount
        );
    }

    private List<MarkerSpan> markerSpans(String line) {
        List<MarkerSpan> markers = new ArrayList<>();
        int searchFrom = 0;
        while (searchFrom < line.length()) {
            int asciiMarker = line.indexOf(CITATION_MARKER, searchFrom);
            int fullWidthMarker = line.indexOf(FULL_WIDTH_CITATION_MARKER, searchFrom);
            int start;
            int markerLength;
            if (asciiMarker < 0 && fullWidthMarker < 0) {
                break;
            } else if (asciiMarker >= 0 && (fullWidthMarker < 0 || asciiMarker < fullWidthMarker)) {
                start = asciiMarker;
                markerLength = CITATION_MARKER.length();
            } else {
                start = fullWidthMarker;
                markerLength = FULL_WIDTH_CITATION_MARKER.length();
            }
            int end = start + markerLength;
            markers.add(new MarkerSpan(start, end));
            searchFrom = end;
        }
        return markers;
    }

    private Gate gate(
            double precision,
            double recall,
            double exactMatchAccuracy,
            int noAnswerCitationCount,
            int unmappedCitationCount,
            int outOfScopeCitationCount,
            int malformedMarkerCount,
            int duplicateCitationCount
    ) {
        List<String> reasons = new ArrayList<>();
        if (precision < 1D || recall < 1D || exactMatchAccuracy < 1D) {
            reasons.add("answer_citation_mismatch");
        }
        if (noAnswerCitationCount > 0) {
            reasons.add("citation_on_no_answer_case");
        }
        if (unmappedCitationCount > 0) {
            reasons.add("citation_not_in_runtime_scope");
        }
        if (outOfScopeCitationCount > 0) {
            reasons.add("citation_outside_retrieved_context");
        }
        if (malformedMarkerCount > 0) {
            reasons.add("malformed_citation_marker");
        }
        if (duplicateCitationCount > 0) {
            reasons.add("duplicate_citation_marker");
        }
        return new Gate(reasons.isEmpty() ? "eligible_for_multimodal_answer_citation_l2" : "inconclusive",
                reasons);
    }

    private record MarkerSpan(int start, int end) {
    }

    private record ParsedCitations(
            List<String> logicalChunkIds,
            List<String> evidenceBackedLogicalChunkIds,
            int runtimeCitationCount,
            int unmappedCitationCount,
            int outOfScopeCitationCount,
            int malformedMarkerCount,
            int duplicateCitationCount
    ) {
    }

    record CaseSummary(
            String caseId,
            List<String> expectedChunkIds,
            List<String> citedChunkIds,
            double precision,
            double recall,
            boolean exactMatch,
            int unmappedCitations,
            int outOfScopeCitations,
            int malformedMarkers,
            int duplicateCitations
    ) {
        CaseSummary {
            expectedChunkIds = List.copyOf(expectedChunkIds);
            citedChunkIds = List.copyOf(citedChunkIds);
        }
    }

    record Gate(String verdict, List<String> reasons) {
        Gate {
            reasons = List.copyOf(reasons);
        }
    }

    record Report(
            String datasetId,
            int caseCount,
            int answerableCaseCount,
            int abstentionCaseCount,
            double meanCitationPrecision,
            double meanCitationRecall,
            double exactCitationMatchAccuracy,
            int noAnswerCitationCount,
            int unmappedCitationCount,
            int outOfScopeCitationCount,
            int malformedCitationMarkerCount,
            int duplicateCitationCount,
            List<CaseSummary> cases,
            Gate gate,
            boolean candidateOnly,
            boolean releaseEligible,
            boolean answerQualityMeasured,
            boolean refusalMeasured
    ) {
        Report {
            cases = List.copyOf(cases);
        }
    }
}
