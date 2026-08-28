package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagIndependentBranchEvaluatorTest {

    @Test
    void shouldRejectNonComparableVariants() {
        RagIndependentBranchEvaluator evaluator = new RagIndependentBranchEvaluator();

        RagIndependentBranchEvaluator.VariantRun r0 = run(
                "R0", fingerprint("scope-sha", "replay-sha"), List.of("case-1", "case-2")
        );
        RagIndependentBranchEvaluator.VariantRun r1 = run(
                "R1", fingerprint("different-scope-sha", "replay-sha"), List.of("case-1", "case-2")
        );
        RagIndependentBranchEvaluator.VariantRun r2 = run(
                "R2", fingerprint("scope-sha", "replay-sha"), List.of("case-1", "case-2")
        );

        assertThatThrownBy(() -> evaluator.evaluate(r0, r1, r2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("R0/R1/R2 冻结输入不一致");
    }

    @Test
    void shouldMarkRerankComparisonInconclusiveWhenR2MissesQualitySafetyOrLatencyGates() {
        RagIndependentBranchEvaluator evaluator = new RagIndependentBranchEvaluator();
        RagIndependentBranchEvaluator.EvaluationFingerprint fingerprint = fingerprint("scope-sha", "replay-sha");
        RagIndependentBranchEvaluator.VariantRun r0 = runReplays("R0", fingerprint, List.of(
                answerableReplay("answerable", List.of("gold-answerable"), 100L, false),
                abstentionReplay("abstain", List.of(), 100L, false)
        ));
        RagIndependentBranchEvaluator.VariantRun r1 = runReplays("R1", fingerprint, List.of(
                answerableReplay("answerable", List.of("gold-answerable"), 100L, false),
                abstentionReplay("abstain", List.of(), 100L, false)
        ));
        RagIndependentBranchEvaluator.VariantRun r2 = runReplays("R2", fingerprint, List.of(
                answerableReplay("answerable", List.of("miss", "gold-answerable"), 116L, true),
                abstentionReplay("abstain", List.of("miss"), 116L, false)
        ));

        assertGate(
                evaluator.evaluate(r0, r1, r2),
                "inconclusive",
                List.of(
                        "r2_quality_below_r0",
                        "r2_p95_latency_above_15_percent",
                        "abstention_violations",
                        "permission_violations"
                )
        );
    }

    @Test
    void shouldAllowRerankComparisonOnlyWhenR2MeetsEveryGate() {
        RagIndependentBranchEvaluator evaluator = new RagIndependentBranchEvaluator();
        RagIndependentBranchEvaluator.EvaluationFingerprint fingerprint = fingerprint("scope-sha", "replay-sha");
        RagIndependentBranchEvaluator.VariantRun r0 = runReplays("R0", fingerprint, List.of(
                answerableReplay("answerable", List.of("gold-answerable"), 100L, false),
                abstentionReplay("abstain", List.of(), 100L, false)
        ));
        RagIndependentBranchEvaluator.VariantRun r1 = runReplays("R1", fingerprint, List.of(
                answerableReplay("answerable", List.of("gold-answerable"), 100L, false),
                abstentionReplay("abstain", List.of(), 100L, false)
        ));
        RagIndependentBranchEvaluator.VariantRun r2 = runReplays("R2", fingerprint, List.of(
                answerableReplay("answerable", List.of("gold-answerable"), 115L, false),
                abstentionReplay("abstain", List.of(), 115L, false)
        ));

        assertGate(evaluator.evaluate(r0, r1, r2), "eligible_for_rerank_ab", List.of());
    }

    @Test
    void shouldExcludeRankElevenHitsFromMrrAndNdcgAtTen() {
        RagIndependentBranchEvaluator evaluator = new RagIndependentBranchEvaluator();
        RagIndependentBranchEvaluator.EvaluationFingerprint fingerprint = fingerprint("scope-sha", "replay-sha");
        RagIndependentBranchEvaluator.VariantRun r0 = runReplays("R0", fingerprint, List.of(
                answerableReplay("answerable", List.of(
                        "miss-1", "miss-2", "miss-3", "miss-4", "miss-5", "miss-6", "miss-7", "miss-8", "miss-9", "gold-answerable"
                ), 100L, false)
        ));
        RagIndependentBranchEvaluator.VariantRun r1 = runReplays("R1", fingerprint, r0.queryReplays());
        RagIndependentBranchEvaluator.VariantRun r2 = runReplays("R2", fingerprint, List.of(
                answerableReplay("answerable", List.of(
                        "miss-1", "miss-2", "miss-3", "miss-4", "miss-5", "miss-6", "miss-7", "miss-8", "miss-9", "miss-10", "gold-answerable"
                ), 100L, false)
        ));

        RagIndependentBranchEvaluator.EvaluationReport report = evaluator.evaluate(r0, r1, r2);

        assertThat(report.variants().get(2).mrrAt10()).isZero();
        assertThat(report.variants().get(2).ndcgAt10()).isZero();
        assertGate(report, "inconclusive", List.of("r2_quality_below_r0"));
    }

    @Test
    void shouldCompareAllGoldChunksInNdcgAtTen() {
        RagIndependentBranchEvaluator evaluator = new RagIndependentBranchEvaluator();
        RagIndependentBranchEvaluator.EvaluationFingerprint fingerprint = fingerprint("scope-sha", "replay-sha");
        Set<String> goldChunkIds = Set.of("gold-primary", "gold-secondary");
        RagIndependentBranchEvaluator.VariantRun r0 = runReplays("R0", fingerprint, List.of(
                replay("answerable", goldChunkIds, List.of("gold-primary", "gold-secondary"), 100L, false, false)
        ));
        RagIndependentBranchEvaluator.VariantRun r1 = runReplays("R1", fingerprint, r0.queryReplays());
        RagIndependentBranchEvaluator.VariantRun r2 = runReplays("R2", fingerprint, List.of(
                replay("answerable", goldChunkIds, List.of("gold-primary", "miss"), 100L, false, false)
        ));

        RagIndependentBranchEvaluator.EvaluationReport report = evaluator.evaluate(r0, r1, r2);

        assertThat(report.variants().get(2).ndcgAt10()).isLessThan(report.variants().get(0).ndcgAt10());
        assertGate(report, "inconclusive", List.of("r2_quality_below_r0"));
    }

    @Test
    void shouldRejectRunsWithDuplicatedRankedChunkIds() {
        RagIndependentBranchEvaluator evaluator = new RagIndependentBranchEvaluator();
        RagIndependentBranchEvaluator.EvaluationFingerprint fingerprint = fingerprint("scope-sha", "replay-sha");
        Set<String> goldChunkIds = Set.of("gold-primary", "gold-secondary");
        RagIndependentBranchEvaluator.VariantRun r0 = runReplays("R0", fingerprint, List.of(
                replay("answerable", goldChunkIds, List.of("gold-primary", "gold-secondary"), 100L, false, false)
        ));
        RagIndependentBranchEvaluator.VariantRun r1 = runReplays("R1", fingerprint, r0.queryReplays());
        RagIndependentBranchEvaluator.VariantRun r2 = runReplays("R2", fingerprint, List.of(
                replay("answerable", goldChunkIds, List.of("gold-primary", "gold-primary"), 100L, false, false)
        ));

        assertThatThrownBy(() -> evaluator.evaluate(r0, r1, r2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("独立三路 query replay 无效");
    }

    @Test
    void shouldRejectVariantsWithDifferentFrozenGoldLabels() {
        RagIndependentBranchEvaluator evaluator = new RagIndependentBranchEvaluator();
        RagIndependentBranchEvaluator.EvaluationFingerprint fingerprint = fingerprint("scope-sha", "replay-sha");
        RagIndependentBranchEvaluator.VariantRun r0 = runReplays("R0", fingerprint, List.of(
                answerableReplay("case-1", List.of("gold-case-1"), 100L, false)
        ));
        RagIndependentBranchEvaluator.VariantRun r1 = runReplays("R1", fingerprint, r0.queryReplays());
        RagIndependentBranchEvaluator.VariantRun r2 = runReplays("R2", fingerprint, List.of(
                replay("case-1", Set.of("another-gold"), List.of("another-gold"), 100L, false, false)
        ));

        assertThatThrownBy(() -> evaluator.evaluate(r0, r1, r2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("R0/R1/R2 冻结输入不一致");
    }

    @Test
    void shouldRejectVariantsWithDifferentFrozenAbstentionLabels() {
        RagIndependentBranchEvaluator evaluator = new RagIndependentBranchEvaluator();
        RagIndependentBranchEvaluator.EvaluationFingerprint fingerprint = fingerprint("scope-sha", "replay-sha");
        RagIndependentBranchEvaluator.VariantRun r0 = runReplays("R0", fingerprint, List.of(
                abstentionReplay("case-1", List.of(), 100L, false)
        ));
        RagIndependentBranchEvaluator.VariantRun r1 = runReplays("R1", fingerprint, r0.queryReplays());
        RagIndependentBranchEvaluator.VariantRun r2 = runReplays("R2", fingerprint, List.of(
                replay("case-1", Set.of(), List.of(), 100L, false, false)
        ));

        assertThatThrownBy(() -> evaluator.evaluate(r0, r1, r2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("R0/R1/R2 冻结输入不一致");
    }

    @Test
    void shouldRejectRunsWithIncompleteEvaluationFingerprint() {
        RagIndependentBranchEvaluator evaluator = new RagIndependentBranchEvaluator();
        RagIndependentBranchEvaluator.EvaluationFingerprint incomplete = new RagIndependentBranchEvaluator.EvaluationFingerprint(
                null, null, null, null, null, 10, 50, null
        );
        RagIndependentBranchEvaluator.VariantRun r0 = run("R0", incomplete, List.of("case-1"));
        RagIndependentBranchEvaluator.VariantRun r1 = run("R1", incomplete, List.of("case-1"));
        RagIndependentBranchEvaluator.VariantRun r2 = run("R2", incomplete, List.of("case-1"));

        assertThatThrownBy(() -> evaluator.evaluate(r0, r1, r2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("独立三路评测臂无效");
    }

    @Test
    void shouldRejectRunsWhoseTopKCannotSupportAtTenMetrics() {
        RagIndependentBranchEvaluator evaluator = new RagIndependentBranchEvaluator();
        String sha256 = "a".repeat(64);
        RagIndependentBranchEvaluator.EvaluationFingerprint insufficientTopK = new RagIndependentBranchEvaluator.EvaluationFingerprint(
                sha256, sha256, sha256, sha256, sha256, 9, 50, sha256
        );
        RagIndependentBranchEvaluator.VariantRun r0 = run("R0", insufficientTopK, List.of("case-1"));
        RagIndependentBranchEvaluator.VariantRun r1 = run("R1", insufficientTopK, List.of("case-1"));
        RagIndependentBranchEvaluator.VariantRun r2 = run("R2", insufficientTopK, List.of("case-1"));

        assertThatThrownBy(() -> evaluator.evaluate(r0, r1, r2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("独立三路评测臂无效");
    }

    @Test
    void shouldRejectR2WhenP95ExceedsGateWithoutLongOverflow() {
        RagIndependentBranchEvaluator evaluator = new RagIndependentBranchEvaluator();
        RagIndependentBranchEvaluator.EvaluationFingerprint fingerprint = fingerprint("scope-sha", "replay-sha");
        RagIndependentBranchEvaluator.VariantRun r0 = runReplays("R0", fingerprint, List.of(
                answerableReplay("answerable", List.of("gold-answerable"), 80_000_000_000_000_000L, false)
        ));
        RagIndependentBranchEvaluator.VariantRun r1 = runReplays("R1", fingerprint, r0.queryReplays());
        RagIndependentBranchEvaluator.VariantRun r2 = runReplays("R2", fingerprint, List.of(
                answerableReplay("answerable", List.of("gold-answerable"), 100_000_000_000_000_000L, false)
        ));

        assertGate(
                evaluator.evaluate(r0, r1, r2),
                "inconclusive",
                List.of("r2_p95_latency_above_15_percent")
        );
    }

    private RagIndependentBranchEvaluator.VariantRun run(
            String variant,
            RagIndependentBranchEvaluator.EvaluationFingerprint fingerprint,
            List<String> caseIds
    ) {
        return runReplays(variant, fingerprint, caseIds.stream().map(caseId -> answerableReplay(
                caseId, List.of("gold-" + caseId), 10L, false
        )).toList());
    }

    private RagIndependentBranchEvaluator.VariantRun runReplays(
            String variant,
            RagIndependentBranchEvaluator.EvaluationFingerprint fingerprint,
            List<RagIndependentBranchEvaluator.QueryReplay> queryReplays
    ) {
        return new RagIndependentBranchEvaluator.VariantRun(
                variant,
                fingerprint,
                queryReplays
        );
    }

    private RagIndependentBranchEvaluator.QueryReplay answerableReplay(
            String caseId,
            List<String> rankedChunkIds,
            long latencyMs,
            boolean permissionViolation
    ) {
        String goldChunkId = "gold-" + caseId;
        return replay(caseId, Set.of(goldChunkId), rankedChunkIds, latencyMs, false, permissionViolation);
    }

    private RagIndependentBranchEvaluator.QueryReplay abstentionReplay(
            String caseId,
            List<String> rankedChunkIds,
            long latencyMs,
            boolean permissionViolation
    ) {
        return replay(caseId, Set.of(), rankedChunkIds, latencyMs, true, permissionViolation);
    }

    private RagIndependentBranchEvaluator.QueryReplay replay(
            String caseId,
            Set<String> goldChunkIds,
            List<String> rankedChunkIds,
            long latencyMs,
            boolean shouldAbstain,
            boolean permissionViolation
    ) {
        return new RagIndependentBranchEvaluator.QueryReplay(
                caseId,
                goldChunkIds,
                rankedChunkIds,
                latencyMs,
                List.of(new RagIndependentBranchEvaluator.BranchDiagnostic(
                        "dense-original", rankedChunkIds.size(), rankedChunkIds.size(), goldChunkIds, 1
                )),
                shouldAbstain,
                permissionViolation
        );
    }

    private void assertGate(
            RagIndependentBranchEvaluator.EvaluationReport report,
            String expectedVerdict,
            List<String> expectedReasons
    ) {
        assertThat(report.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .contains("gate");
        Object gate = recordComponentValue(report, "gate");
        assertThat(recordComponentValue(gate, "verdict")).isEqualTo(expectedVerdict);
        assertThat(recordComponentValue(gate, "reasons")).isEqualTo(expectedReasons);
    }

    private Object recordComponentValue(Object record, String componentName) {
        try {
            return record.getClass().getMethod(componentName).invoke(record);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("缺少评测报告字段: " + componentName, exception);
        }
    }

    private RagIndependentBranchEvaluator.EvaluationFingerprint fingerprint(String scopeSha256, String replaySha256) {
        return new RagIndependentBranchEvaluator.EvaluationFingerprint(
                "g2-pre-bm25-v1",
                testSha256("gold-sha"),
                testSha256(scopeSha256),
                testSha256(replaySha256),
                testSha256("branch-config-sha"),
                10,
                50,
                testSha256("effective-query-set-sha")
        );
    }

    private String testSha256(String value) {
        return String.format("%064x", value.hashCode() & 0xffffffffL);
    }
}
