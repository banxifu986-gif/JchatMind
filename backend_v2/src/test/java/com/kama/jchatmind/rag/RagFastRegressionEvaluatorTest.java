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
        assertTrue(Files.exists(reportPath));
        assertTrue(Files.readString(reportPath).contains("fixture-fast-v1"));
    }
}
