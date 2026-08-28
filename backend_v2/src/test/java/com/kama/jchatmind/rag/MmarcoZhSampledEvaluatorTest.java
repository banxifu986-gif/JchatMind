package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmarcoZhSampledEvaluatorTest {

    private final MmarcoZhSampledEvaluator evaluator = new MmarcoZhSampledEvaluator();

    @Test
    void mapsEveryPassageToAStableChunkId() {
        Map<String, String> mapping = evaluator.stableChunkIdMapping(List.of("42", "doc-17"));

        assertEquals(Map.of("42", "mmarco:zh:42", "doc-17", "mmarco:zh:doc-17"), mapping);
        assertThrows(IllegalStateException.class, () -> evaluator.stableChunkIdMapping(List.of("42", "42")));
    }

    @Test
    void rejectsRerankComparisonWhenFrozenInputsDiffer() {
        MmarcoZhSampledEvaluator.VariantRun local = new MmarcoZhSampledEvaluator.VariantRun(
                "local-rule-rerank", fingerprint("manifest-a"), List.of(replay("q-1", false))
        );
        MmarcoZhSampledEvaluator.VariantRun tei = new MmarcoZhSampledEvaluator.VariantRun(
                "tei-bge-rerank", fingerprint("manifest-b"), List.of(replay("q-1", false))
        );

        assertThrows(IllegalStateException.class, () -> evaluator.compare(local, tei));
    }

    @Test
    void invalidatesTheWholeTeiArmWhenAnyQueryFallsBack() {
        MmarcoZhSampledEvaluator.VariantRun local = new MmarcoZhSampledEvaluator.VariantRun(
                "local-rule-rerank", fingerprint("manifest-a"), List.of(replay("q-1", false), replay("q-2", false))
        );
        MmarcoZhSampledEvaluator.VariantRun tei = new MmarcoZhSampledEvaluator.VariantRun(
                "tei-bge-rerank", fingerprint("manifest-a"), List.of(replay("q-1", false), replay("q-2", true))
        );

        MmarcoZhSampledEvaluator.EvaluationReport report = evaluator.evaluate(tei);

        assertEquals("invalid", report.status());
        assertEquals(1, report.invalidCount());
        assertEquals(0.5D, report.teiSuccessRate(), 0.0001D);
        assertEquals("invalid_tei_fallback", evaluator.compare(local, tei).status());
    }

    @Test
    void reportsRetrievalAndIdBasedContextMetricsWithBootstrapComparison() {
        MmarcoZhSampledEvaluator.VariantRun local = new MmarcoZhSampledEvaluator.VariantRun(
                "local-rule-rerank", fingerprint("manifest-a"), List.of(
                        replay("q-1", false),
                        new MmarcoZhSampledEvaluator.QueryReplay(
                                "q-2", Set.of("mmarco:zh:2"), List.of("noise", "mmarco:zh:2"), 20, false
                        )
                )
        );
        MmarcoZhSampledEvaluator.VariantRun tei = new MmarcoZhSampledEvaluator.VariantRun(
                "tei-bge-rerank", fingerprint("manifest-a"), List.of(
                        replay("q-1", false),
                        new MmarcoZhSampledEvaluator.QueryReplay(
                                "q-2", Set.of("mmarco:zh:2"), List.of("mmarco:zh:2", "noise"), 22, false
                        )
                )
        );

        MmarcoZhSampledEvaluator.EvaluationReport report = evaluator.evaluate(tei);
        MmarcoZhSampledEvaluator.Comparison comparison = evaluator.compare(local, tei);

        assertEquals("valid", report.status());
        assertEquals(1D, report.metrics().recallAt1(), 0.0001D);
        assertEquals(1D, report.metrics().recallAt3(), 0.0001D);
        assertEquals(1D, report.metrics().recallAt5(), 0.0001D);
        assertEquals(1D, report.metrics().recallAt10(), 0.0001D);
        assertEquals(1D, report.metrics().mrrAt10(), 0.0001D);
        assertEquals(1D, report.metrics().ndcgAt10(), 0.0001D);
        assertEquals(1D, report.metrics().contextPrecisionAt10(), 0.0001D);
        assertEquals(1D, report.metrics().contextRecallAt10(), 0.0001D);
        assertEquals(1_000, comparison.bootstrap().samples());
        assertTrue(comparison.bootstrap().mrrAt10().pointEstimate() > 0D);
        assertTrue(comparison.bootstrap().ndcgAt10().pointEstimate() > 0D);
        assertTrue(comparison.bootstrap().mrrAt10().lowerBound() <= comparison.bootstrap().mrrAt10().pointEstimate());
        assertTrue(comparison.bootstrap().mrrAt10().pointEstimate() <= comparison.bootstrap().mrrAt10().upperBound());
    }

    private MmarcoZhSampledEvaluator.EvaluationFingerprint fingerprint(String candidateManifestSha256) {
        return new MmarcoZhSampledEvaluator.EvaluationFingerprint(
                "mmarco-zh-sampled-v1",
                "source-sha",
                candidateManifestSha256,
                "mmarco-zh-passage-id-v1",
                "vchord-bm25-v1",
                "bge-m3",
                "bm25-dictionary-v1",
                "rrf-k-60",
                10,
                50,
                "query-set-sha"
        );
    }

    private MmarcoZhSampledEvaluator.QueryReplay replay(String queryId, boolean teiFallback) {
        return new MmarcoZhSampledEvaluator.QueryReplay(
                queryId,
                Set.of("mmarco:zh:1"),
                List.of("mmarco:zh:1", "noise"),
                10,
                teiFallback
        );
    }
}
