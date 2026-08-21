package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagFastRegressionEvaluatorTest {

    @Test
    void evaluatesFrozenReplayWithoutSpringOrEmbedding() throws Exception {
        Path reportPath = Path.of("target", "rag-eval", "fast", "fixture-fast-v1-report.json");
        RagFastRegressionReport report = RagFastRegressionEvaluator.evaluateAndWrite(
                "rag-eval/datasets/manifests/fixture-fast-v1.json",
                "rag-eval/datasets/replays/fixture-fast-v1.jsonl",
                reportPath
        );

        assertEquals("fixture-fast-v1", report.datasetId());
        assertEquals("replay", report.executionMode());
        assertTrue(report.manifestSha256().matches("[0-9a-f]{64}"));
        assertEquals(20, report.total());
        assertEquals(18, report.answerable());
        assertEquals(2, report.abstentionTotal());
        assertEquals(1.0, report.recallAt5(), 0.0001);
        assertEquals(1.0, report.contextRecallAt5(), 0.0001);
        assertEquals(1.0, report.contextRecallAt10(), 0.0001);
        assertEquals(report.contextPrecisionAt5(), report.contextPrecisionAt10(), 0.0001);
        assertTrue(report.mrrAt3() < 1.0);
        assertEquals(1.0, report.abstentionAccuracy(), 0.0001);
        assertEquals("disabled", report.ragas().status());
        assertEquals(report.contextPrecisionAt5(), report.ragas().contextPrecisionAt5(), 0.0001);
        assertEquals(report.contextRecallAt5(), report.ragas().contextRecallAt5(), 0.0001);
        assertEquals(0, report.ragas().evaluated());
        assertEquals(0, report.ragas().skipped());
        assertEquals(null, report.ragas().faithfulness());
        assertEquals(null, report.ragas().answerRelevancy());
        assertTrue(Files.exists(reportPath));
        String reportJson = Files.readString(reportPath);
        assertTrue(reportJson.contains("fixture-fast-v1"));
        assertTrue(reportJson.contains("\"ragas\""));
        assertTrue(reportJson.contains("\"p95LatencyMs\" : 27"));
    }

    @Test
    void evaluatesFrozenG2PreBm25Replay() throws Exception {
        Path reportPath = Path.of("target", "rag-eval", "fast", "g2-pre-bm25-v1-report.json");

        RagFastRegressionReport report = RagFastRegressionEvaluator.evaluateAndWrite(
                "rag-eval/datasets/manifests/g2-pre-bm25-v1.json",
                "rag-eval/datasets/replays/g2-pre-bm25-v1.jsonl",
                reportPath
        );

        assertEquals("g2-pre-bm25-v1", report.datasetId());
        assertEquals(9, report.total());
        assertEquals(7, report.answerable());
        assertEquals(2, report.abstentionTotal());
        assertEquals(1.0, report.recallAt5(), 0.0001);
        assertEquals(1.0, report.abstentionAccuracy(), 0.0001);
        assertEquals(45, report.p95LatencyMs());
        assertTrue(Files.exists(reportPath));
    }
}
