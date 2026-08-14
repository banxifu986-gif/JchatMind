package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagAsMetricsTest {

    @Test
    void contextPrecisionRewardsRelevantChunksNearTheTop() {
        assertEquals(1.0, RagAsMetrics.contextPrecision(List.of("gold-a", "gold-b", "noise"), Set.of("gold-a", "gold-b")), 0.0001);
        assertEquals(0.5833, RagAsMetrics.contextPrecision(List.of("noise", "gold-a", "gold-b"), Set.of("gold-a", "gold-b")), 0.0001);
    }

    @Test
    void contextRecallMeasuresMultipleGoldChunksCoveredByResults() {
        assertEquals(0.5, RagAsMetrics.contextRecall(List.of("gold-a", "noise"), Set.of("gold-a", "gold-b")), 0.0001);
        assertEquals(1.0, RagAsMetrics.contextRecall(List.of("gold-a", "gold-b"), Set.of("gold-a", "gold-b")), 0.0001);
    }

    @Test
    void emptyInputsAndJudgeScoresAreHandledSafely() {
        assertEquals(0D, RagAsMetrics.contextPrecision(List.of(), Set.of("gold")), 0.0001);
        assertEquals(0D, RagAsMetrics.contextRecall(List.of("noise"), Set.of()), 0.0001);
        assertEquals(0D, RagAsMetrics.clampScore(-0.2), 0.0001);
        assertEquals(1D, RagAsMetrics.clampScore(1.2), 0.0001);
    }
}
