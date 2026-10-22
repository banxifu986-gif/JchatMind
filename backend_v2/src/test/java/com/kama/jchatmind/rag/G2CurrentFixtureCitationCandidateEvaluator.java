package com.kama.jchatmind.rag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class G2CurrentFixtureCitationCandidateEvaluator {

    private static final String CITATION_MARKER = "引用:";
    private static final String FULL_WIDTH_CITATION_MARKER = "引用：";
    private static final Pattern RUNTIME_UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b"
    );

    Report evaluate(
            G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate,
            Map<String, String> logicalChunkIdByRuntimeUuid,
            Map<String, Set<String>> retrievedRuntimeChunkIdsByCase,
            Map<String, String> finalAnswersByCaseId
    ) {
        Map<String, G2CurrentFixtureCitationCandidateLoader.CandidateCase> casesById = indexCases(candidate);
        validateInputs(candidate, logicalChunkIdByRuntimeUuid, retrievedRuntimeChunkIdsByCase,
                finalAnswersByCaseId, casesById);

        List<CaseSummary> summaries = new ArrayList<>(candidate.cases().size());
        int answerableCount = 0;
        int noAnswerCount = 0;
        int exactMatches = 0;
        int noAnswerCitationCount = 0;
        int unmappedCitationCount = 0;
        int outOfScopeCitationCount = 0;
        int malformedMarkerCount = 0;
        int duplicateCitationCount = 0;
        double precisionSum = 0D;
        double recallSum = 0D;

        for (G2CurrentFixtureCitationCandidateLoader.CandidateCase item : candidate.cases()) {
            ParsedCitations parsed = parseCitations(finalAnswersByCaseId.get(item.caseId()),
                    logicalChunkIdByRuntimeUuid, retrievedRuntimeChunkIdsByCase.get(item.caseId()));
            int citationCount = parsed.runtimeCitationCount() + parsed.malformedMarkerCount();
            unmappedCitationCount += parsed.unmappedCitationCount();
            outOfScopeCitationCount += parsed.outOfScopeCitationCount();
            malformedMarkerCount += parsed.malformedMarkerCount();
            duplicateCitationCount += parsed.duplicateCitationCount();

            if (item.shouldAbstain()) {
                noAnswerCount++;
                noAnswerCitationCount += citationCount;
                summaries.add(new CaseSummary(item.caseId(), List.of(), parsed.logicalChunkIds(),
                        0D, 0D, citationCount == 0, 0));
                continue;
            }

            answerableCount++;
            Set<String> expected = new HashSet<>();
            item.goldCitationGroups().forEach(expected::addAll);
            Set<String> citedExpected = new HashSet<>(parsed.evidenceBackedLogicalChunkIds());
            citedExpected.retainAll(expected);
            int matchedGroups = (int) item.goldCitationGroups().stream()
                    .filter(group -> parsed.evidenceBackedLogicalChunkIds().stream().anyMatch(group::contains))
                    .count();
            double precision = citationCount == 0 ? 0D : citedExpected.size() / (double) citationCount;
            double recall = matchedGroups / (double) item.goldCitationGroups().size();
            boolean exact = matchedGroups == item.goldCitationGroups().size()
                    && citationCount == item.goldCitationGroups().size()
                    && parsed.unmappedCitationCount() == 0
                    && parsed.outOfScopeCitationCount() == 0
                    && parsed.malformedMarkerCount() == 0
                    && parsed.duplicateCitationCount() == 0;
            precisionSum += precision;
            recallSum += recall;
            if (exact) {
                exactMatches++;
            }
            summaries.add(new CaseSummary(item.caseId(), item.goldCitationGroups(), parsed.logicalChunkIds(),
                    precision, recall, exact, matchedGroups));
        }

        double precision = answerableCount == 0 ? 0D : precisionSum / answerableCount;
        double recall = answerableCount == 0 ? 0D : recallSum / answerableCount;
        double exactAccuracy = answerableCount == 0 ? 0D : exactMatches / (double) answerableCount;
        Gate gate = gate(precision, recall, exactAccuracy, noAnswerCitationCount, unmappedCitationCount,
                outOfScopeCitationCount, malformedMarkerCount, duplicateCitationCount);
        return new Report(candidate.datasetId(), candidate.cases().size(), answerableCount, noAnswerCount,
                precision, recall, exactAccuracy, noAnswerCitationCount, unmappedCitationCount,
                outOfScopeCitationCount, malformedMarkerCount, duplicateCitationCount,
                List.copyOf(summaries), gate, true, false);
    }

    private Map<String, G2CurrentFixtureCitationCandidateLoader.CandidateCase> indexCases(
            G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate
    ) {
        if (candidate == null || !"candidate".equals(candidate.status()) || candidate.cases() == null) {
            throw new IllegalArgumentException("citation candidate evaluator 只接受 candidate 数据集");
        }
        Map<String, G2CurrentFixtureCitationCandidateLoader.CandidateCase> indexed = new LinkedHashMap<>();
        for (G2CurrentFixtureCitationCandidateLoader.CandidateCase item : candidate.cases()) {
            if (item == null || item.caseId() == null || item.caseId().isBlank()
                    || indexed.put(item.caseId(), item) != null) {
                throw new IllegalArgumentException("citation candidate caseId 缺失或重复");
            }
            if (item.shouldAbstain() ? !item.goldCitationGroups().isEmpty() : item.goldCitationGroups().isEmpty()) {
                throw new IllegalArgumentException("citation candidate case gold groups 与拒答状态不匹配: "
                        + item.caseId());
            }
        }
        return indexed;
    }

    private void validateInputs(
            G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate,
            Map<String, String> logicalChunkIdByRuntimeUuid,
            Map<String, Set<String>> retrievedRuntimeChunkIdsByCase,
            Map<String, String> finalAnswersByCaseId,
            Map<String, G2CurrentFixtureCitationCandidateLoader.CandidateCase> casesById
    ) {
        if (logicalChunkIdByRuntimeUuid == null || logicalChunkIdByRuntimeUuid.isEmpty()
                || retrievedRuntimeChunkIdsByCase == null
                || !retrievedRuntimeChunkIdsByCase.keySet().equals(casesById.keySet())
                || finalAnswersByCaseId == null || !finalAnswersByCaseId.keySet().equals(casesById.keySet())) {
            throw new IllegalStateException("citation candidate answers/retrieval/runtime mapping 必须精确覆盖 cases");
        }
        Set<String> logicalChunkIds = new HashSet<>(logicalChunkIdByRuntimeUuid.values());
        Set<String> runtimeChunkIds = logicalChunkIdByRuntimeUuid.keySet();
        for (G2CurrentFixtureCitationCandidateLoader.CandidateCase item : candidate.cases()) {
            if (!item.shouldAbstain() && !logicalChunkIds.containsAll(
                    item.goldCitationGroups().stream().flatMap(List::stream).toList())) {
                throw new IllegalStateException("citation candidate runtime map 缺少 gold alternative: "
                        + item.caseId());
            }
            if (finalAnswersByCaseId.get(item.caseId()) == null) {
                throw new IllegalStateException("citation candidate final answer 为 null: " + item.caseId());
            }
            Set<String> retrievedIds = retrievedRuntimeChunkIdsByCase.get(item.caseId());
            if (retrievedIds == null || !runtimeChunkIds.containsAll(retrievedIds)) {
                throw new IllegalStateException("citation candidate retrieval set 含未知 runtime UUID: "
                        + item.caseId());
            }
        }
    }

    private ParsedCitations parseCitations(
            String answer,
            Map<String, String> logicalChunkIdByRuntimeUuid,
            Set<String> retrievedRuntimeChunkIds
    ) {
        List<String> logicalIds = new ArrayList<>();
        List<String> evidenceBackedIds = new ArrayList<>();
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
                Matcher matcher = RUNTIME_UUID.matcher(line.substring(marker.end(), end));
                if (!matcher.find()) {
                    malformedMarkerCount++;
                    continue;
                }
                runtimeCitationCount++;
                String runtimeUuid = matcher.group().toLowerCase(Locale.ROOT);
                if (!seenRuntimeIds.add(runtimeUuid)) {
                    duplicateCitationCount++;
                    continue;
                }
                String logicalId = logicalChunkIdByRuntimeUuid.get(runtimeUuid);
                if (logicalId == null) {
                    unmappedCitationCount++;
                    continue;
                }
                logicalIds.add(logicalId);
                if (retrievedRuntimeChunkIds.contains(runtimeUuid)) {
                    evidenceBackedIds.add(logicalId);
                } else {
                    outOfScopeCitationCount++;
                }
            }
        }
        return new ParsedCitations(List.copyOf(logicalIds), List.copyOf(evidenceBackedIds),
                runtimeCitationCount, unmappedCitationCount, outOfScopeCitationCount,
                malformedMarkerCount, duplicateCitationCount);
    }

    private List<MarkerSpan> markerSpans(String line) {
        List<MarkerSpan> markers = new ArrayList<>();
        int searchFrom = 0;
        while (searchFrom < line.length()) {
            int ascii = line.indexOf(CITATION_MARKER, searchFrom);
            int fullWidth = line.indexOf(FULL_WIDTH_CITATION_MARKER, searchFrom);
            int start;
            int length;
            if (ascii < 0 && fullWidth < 0) {
                break;
            } else if (ascii >= 0 && (fullWidth < 0 || ascii < fullWidth)) {
                start = ascii;
                length = CITATION_MARKER.length();
            } else {
                start = fullWidth;
                length = FULL_WIDTH_CITATION_MARKER.length();
            }
            markers.add(new MarkerSpan(start, start + length));
            searchFrom = start + length;
        }
        return markers;
    }

    private Gate gate(double precision, double recall, double exactAccuracy, int noAnswerCitationCount,
                      int unmappedCitationCount, int outOfScopeCitationCount,
                      int malformedMarkerCount, int duplicateCitationCount) {
        List<String> reasons = new ArrayList<>();
        if (precision < 1D || recall < 1D || exactAccuracy < 1D) {
            reasons.add("answer_citation_mismatch");
        }
        if (noAnswerCitationCount > 0) reasons.add("citation_on_no_answer_case");
        if (unmappedCitationCount > 0) reasons.add("citation_not_in_runtime_scope");
        if (outOfScopeCitationCount > 0) reasons.add("citation_outside_retrieved_context");
        if (malformedMarkerCount > 0) reasons.add("malformed_citation_marker");
        if (duplicateCitationCount > 0) reasons.add("duplicate_citation_marker");
        return new Gate(reasons.isEmpty() ? "eligible_for_candidate_citation_contract" : "inconclusive", reasons);
    }

    private record MarkerSpan(int start, int end) {
    }

    private record ParsedCitations(List<String> logicalChunkIds, List<String> evidenceBackedLogicalChunkIds,
                                   int runtimeCitationCount, int unmappedCitationCount,
                                   int outOfScopeCitationCount, int malformedMarkerCount,
                                   int duplicateCitationCount) {
    }

    record CaseSummary(String caseId, List<List<String>> expectedCitationGroups, List<String> citedChunkIds,
                       double precision, double recall, boolean exactMatch, int matchedEvidenceGroups) {
        CaseSummary {
            expectedCitationGroups = expectedCitationGroups.stream().map(List::copyOf).toList();
            citedChunkIds = List.copyOf(citedChunkIds);
        }
    }

    record Gate(String verdict, List<String> reasons) {
        Gate {
            reasons = List.copyOf(reasons);
        }
    }

    record Report(String datasetId, int caseCount, int answerableCaseCount, int noAnswerCaseCount,
                  double meanCitationPrecision, double meanCitationRecall, double exactCitationMatchAccuracy,
                  int noAnswerCitationCount, int unmappedCitationCount, int outOfScopeCitationCount,
                  int malformedCitationMarkerCount, int duplicateCitationCount, List<CaseSummary> cases,
                  Gate gate, boolean candidateOnly, boolean releaseEligible) {
        Report {
            cases = List.copyOf(cases);
        }

        CaseSummary caseById(String caseId) {
            return cases.stream().filter(item -> item.caseId().equals(caseId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("citation candidate case 不存在: " + caseId));
        }
    }
}
