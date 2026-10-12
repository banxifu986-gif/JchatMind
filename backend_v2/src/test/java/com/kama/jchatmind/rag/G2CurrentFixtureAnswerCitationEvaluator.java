package com.kama.jchatmind.rag;

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

final class G2CurrentFixtureAnswerCitationEvaluator {

    private static final String CITATION_MARKER = "引用:";
    private static final String FULL_WIDTH_CITATION_MARKER = "引用：";
    private static final Pattern RUNTIME_UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b"
    );

    Report evaluate(
            G2CurrentFixtureRuntimeCorpus fixture,
            Map<String, String> logicalChunkIdByRuntimeUuid,
            Map<String, Set<String>> retrievedRuntimeChunkIdsByCase,
            Map<String, String> finalAnswersByCaseId
    ) {
        Map<String, RagEvaluationCase> casesById = indexCases(fixture);
        validateInputs(
                fixture,
                logicalChunkIdByRuntimeUuid,
                retrievedRuntimeChunkIdsByCase,
                finalAnswersByCaseId,
                casesById
        );

        List<CaseSummary> caseSummaries = new ArrayList<>(casesById.size());
        int answerableCases = 0;
        int noAnswerCases = 0;
        int exactCitationMatches = 0;
        int noAnswerCitationCount = 0;
        int unmappedCitationCount = 0;
        int outOfScopeCitationCount = 0;
        int malformedCitationMarkerCount = 0;
        int duplicateCitationCount = 0;
        double precisionSum = 0D;
        double recallSum = 0D;

        for (RagEvaluationCase evaluationCase : fixture.cases()) {
            ParsedCitations parsed = parseCitations(
                    finalAnswersByCaseId.get(evaluationCase.caseId()),
                    logicalChunkIdByRuntimeUuid,
                    retrievedRuntimeChunkIdsByCase.get(evaluationCase.caseId())
            );
            int citationCount = parsed.runtimeCitationCount() + parsed.malformedMarkerCount();
            unmappedCitationCount += parsed.unmappedCitationCount();
            outOfScopeCitationCount += parsed.outOfScopeCitationCount();
            malformedCitationMarkerCount += parsed.malformedMarkerCount();
            duplicateCitationCount += parsed.duplicateCitationCount();

            if (evaluationCase.shouldAbstain()) {
                noAnswerCases++;
                noAnswerCitationCount += citationCount;
                caseSummaries.add(new CaseSummary(
                        evaluationCase.caseId(),
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
            Set<String> expected = Set.copyOf(evaluationCase.goldChunkIds());
            Set<String> cited = new LinkedHashSet<>(parsed.logicalChunkIds());
            long truePositives = parsed.evidenceBackedLogicalChunkIds().stream()
                    .filter(expected::contains)
                    .count();
            double precision = citationCount == 0 ? 0D : truePositives / (double) citationCount;
            double recall = expected.isEmpty() ? 0D : Math.min(1D, truePositives / (double) expected.size());
            boolean exact = cited.equals(expected)
                    && citationCount == expected.size()
                    && parsed.unmappedCitationCount() == 0
                    && parsed.malformedMarkerCount() == 0
                    && parsed.duplicateCitationCount() == 0;
            precisionSum += precision;
            recallSum += recall;
            if (exact) {
                exactCitationMatches++;
            }
            caseSummaries.add(new CaseSummary(
                    evaluationCase.caseId(),
                    evaluationCase.goldChunkIds(),
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
        Gate gate = gate(
                meanPrecision,
                meanRecall,
                exactMatchAccuracy,
                noAnswerCitationCount,
                unmappedCitationCount,
                outOfScopeCitationCount,
                malformedCitationMarkerCount,
                duplicateCitationCount
        );
        return new Report(
                fixture.datasetId(),
                casesById.size(),
                answerableCases,
                noAnswerCases,
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
                false
        );
    }

    private Map<String, RagEvaluationCase> indexCases(G2CurrentFixtureRuntimeCorpus fixture) {
        if (fixture == null || !"fixture-fast-v1".equals(fixture.datasetId()) || fixture.cases() == null) {
            throw new IllegalArgumentException("citation evaluation fixture 无效");
        }
        Map<String, RagEvaluationCase> casesById = new LinkedHashMap<>();
        for (RagEvaluationCase item : fixture.cases()) {
            if (item == null || item.caseId() == null || item.caseId().isBlank()
                    || casesById.put(item.caseId(), item) != null) {
                throw new IllegalArgumentException("citation evaluation caseId 缺失或重复");
            }
            if (item.shouldAbstain()) {
                if (!item.goldChunkIds().isEmpty()) {
                    throw new IllegalArgumentException("no-answer case 不应有 gold citation: " + item.caseId());
                }
            } else if (item.goldChunkIds().isEmpty()) {
                throw new IllegalArgumentException("answerable case 缺少 gold citation: " + item.caseId());
            }
        }
        return casesById;
    }

    private void validateInputs(
            G2CurrentFixtureRuntimeCorpus fixture,
            Map<String, String> logicalChunkIdByRuntimeUuid,
            Map<String, Set<String>> retrievedRuntimeChunkIdsByCase,
            Map<String, String> finalAnswersByCaseId,
            Map<String, RagEvaluationCase> casesById
    ) {
        if (logicalChunkIdByRuntimeUuid == null || logicalChunkIdByRuntimeUuid.isEmpty()
                || retrievedRuntimeChunkIdsByCase == null
                || !retrievedRuntimeChunkIdsByCase.keySet().equals(casesById.keySet())
                || finalAnswersByCaseId == null || !finalAnswersByCaseId.keySet().equals(casesById.keySet())) {
            throw new IllegalStateException("citation evaluation answers/retrieval/runtime mapping 必须精确覆盖冻结 fixture");
        }
        Set<String> logicalChunkIds = new HashSet<>(logicalChunkIdByRuntimeUuid.values());
        Set<String> runtimeChunkIds = logicalChunkIdByRuntimeUuid.keySet();
        for (RagEvaluationCase evaluationCase : fixture.cases()) {
            if (!evaluationCase.shouldAbstain()
                    && !logicalChunkIds.containsAll(evaluationCase.goldChunkIds())) {
                throw new IllegalStateException("citation evaluation runtime map 缺少 gold chunk: "
                        + evaluationCase.caseId());
            }
            if (finalAnswersByCaseId.get(evaluationCase.caseId()) == null) {
                throw new IllegalStateException("citation evaluation final answer 为 null: " + evaluationCase.caseId());
            }
            Set<String> retrievedIds = retrievedRuntimeChunkIdsByCase.get(evaluationCase.caseId());
            if (retrievedIds == null || !runtimeChunkIds.containsAll(retrievedIds)) {
                throw new IllegalStateException("citation evaluation retrieval set 含未知 runtime UUID: "
                        + evaluationCase.caseId());
            }
        }
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
        return new Gate(reasons.isEmpty() ? "eligible_for_answer_citation_ab" : "inconclusive", reasons);
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
            int noAnswerCaseCount,
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
            boolean answerFaithfulnessMeasured
    ) {
        Report {
            cases = List.copyOf(cases);
        }
    }
}
