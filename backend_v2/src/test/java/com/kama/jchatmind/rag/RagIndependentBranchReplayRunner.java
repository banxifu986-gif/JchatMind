package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class RagIndependentBranchReplayRunner {

    private static final int TOP_K = 10;
    private static final int CANDIDATE_BUDGET = 50;
    private static final int RRF_K = 60;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    List<RagIndependentBranchEvaluator.VariantRun> run(
            RagIndependentBranchReplayLoader.FrozenReplay replay,
            Function<RagIndependentBranchReplayLoader.QueryReplay, List<String>> candidates
    ) {
        if (replay == null || candidates == null || replay.cases().isEmpty()) {
            throw new IllegalArgumentException("三路回放运行输入不能为空");
        }
        return List.of(
                runVariant("R0", replay, candidates),
                runVariant("R1", replay, candidates),
                runVariant("R2", replay, candidates)
        );
    }

    void writeReport(
            RagIndependentBranchReplayLoader.FrozenReplay replay,
            List<RagIndependentBranchEvaluator.VariantRun> runs,
            Path reportPath
    ) throws IOException {
        if (runs == null || runs.size() != 3) {
            throw new IllegalArgumentException("三路回放报告必须包含 R0/R1/R2");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("datasetId", replay.datasetId());
        report.put("inputSha256", replay.inputSha256());
        report.put("summary", new RagIndependentBranchEvaluator().evaluate(runs.get(0), runs.get(1), runs.get(2)));
        report.put("variants", runs);
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private RagIndependentBranchEvaluator.VariantRun runVariant(
            String variant,
            RagIndependentBranchReplayLoader.FrozenReplay replay,
            Function<RagIndependentBranchReplayLoader.QueryReplay, List<String>> candidates
    ) {
        List<RagIndependentBranchEvaluator.QueryReplay> queryReplays = new ArrayList<>();
        for (RagIndependentBranchReplayLoader.QueryReplay queryReplay : replay.cases()) {
            long startedAt = System.nanoTime();
            List<String> baseCandidates = queryReplay.shouldAbstain()
                    ? List.of()
                    : uniqueCandidates(candidates.apply(queryReplay));
            Map<String, List<String>> branches = branches(variant, queryReplay, baseCandidates);
            List<String> ranked = outerRrf(branches).stream().limit(TOP_K).toList();
            queryReplays.add(new RagIndependentBranchEvaluator.QueryReplay(
                    queryReplay.caseId(),
                    queryReplay.goldChunkIds(),
                    ranked,
                    Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L),
                    diagnostics(branches, ranked, queryReplay.goldChunkIds()),
                    queryReplay.shouldAbstain(),
                    false
            ));
        }
        return new RagIndependentBranchEvaluator.VariantRun(
                variant,
                fingerprint(variant, replay),
                queryReplays
        );
    }

    private Map<String, List<String>> branches(
            String variant,
            RagIndependentBranchReplayLoader.QueryReplay replay,
            List<String> candidates
    ) {
        Map<String, List<String>> branches = new LinkedHashMap<>();
        if ("R0".equals(variant)) {
            branches.put("current-flat", candidates);
            return branches;
        }
        branches.put("dense-original", candidates);
        branches.put("sparse-original", candidates);
        if ("R2".equals(variant) && replay.retrievalQueries().stream()
                .anyMatch(query -> !"original".equals(query.source()))) {
            branches.put("expanded-query", candidates);
        }
        return branches;
    }

    private List<String> uniqueCandidates(List<String> candidates) {
        if (candidates == null) {
            throw new IllegalStateException("三路回放候选结果不能为空");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                unique.add(candidate);
            }
        }
        return unique.stream().limit(CANDIDATE_BUDGET).toList();
    }

    private List<String> outerRrf(Map<String, List<String>> branches) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> branch : branches.values()) {
            for (int index = 0; index < branch.size(); index++) {
                scores.merge(branch.get(index), 1D / (RRF_K + index + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<RagIndependentBranchEvaluator.BranchDiagnostic> diagnostics(
            Map<String, List<String>> branches,
            List<String> ranked,
            Set<String> goldChunkIds
    ) {
        List<RagIndependentBranchEvaluator.BranchDiagnostic> diagnostics = new ArrayList<>();
        for (Map.Entry<String, List<String>> branch : branches.entrySet()) {
            Set<String> branchGold = branch.getValue().stream()
                    .filter(goldChunkIds::contains)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            int outerRank = ranked.stream()
                    .filter(branchGold::contains)
                    .findFirst()
                    .map(chunkId -> ranked.indexOf(chunkId) + 1)
                    .orElse(-1);
            diagnostics.add(new RagIndependentBranchEvaluator.BranchDiagnostic(
                    branch.getKey(),
                    branch.getValue().size(),
                    new LinkedHashSet<>(branch.getValue()).size(),
                    branchGold,
                    outerRank
            ));
        }
        return List.copyOf(diagnostics);
    }

    private RagIndependentBranchEvaluator.EvaluationFingerprint fingerprint(
            String variant,
            RagIndependentBranchReplayLoader.FrozenReplay replay
    ) {
        String gold = replay.cases().stream()
                .map(item -> item.caseId() + "=" + item.goldChunkIds().stream().sorted().toList())
                .reduce("", (left, right) -> left + "\n" + right);
        String scope = replay.cases().stream()
                .map(item -> item.caseId() + "=" + item.kbScope())
                .reduce("", (left, right) -> left + "\n" + right);
        String effectiveQuerySet = replay.cases().stream()
                .map(RagIndependentBranchReplayLoader.QueryReplay::caseId)
                .reduce("", (left, right) -> left + "\n" + right);
        return new RagIndependentBranchEvaluator.EvaluationFingerprint(
                replay.datasetId(),
                sha256(gold),
                sha256(scope),
                replay.inputSha256(),
                sha256(variant + "|RRF_K=" + RRF_K + "|topK=" + TOP_K + "|candidateBudget=" + CANDIDATE_BUDGET),
                TOP_K,
                CANDIDATE_BUDGET,
                sha256(effectiveQuerySet)
        );
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
