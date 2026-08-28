package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmarcoZhSampledReplayCollectorTest {

    @Test
    void translatesDeterministicRuntimeChunkUuidsToManifestLogicalIds() {
        MmarcoZhSampledReplayCollector collector = new MmarcoZhSampledReplayCollector();
        MmarcoZhSampledEvaluator.VariantRun run = collector.collect(
                "rrf-only",
                fingerprint(),
                List.of(new MmarcoZhSampledDatasetFreezer.Query("q-1", "query one")),
                Map.of("q-1", List.of("mmarco:zh:p-1")),
                Map.of("uuid-p-1", "mmarco:zh:p-1", "uuid-p-2", "mmarco:zh:p-2"),
                List.of(new MmarcoZhSampledReplayCollector.RuntimeQueryResult(
                        "q-1", List.of("uuid-p-2", "uuid-p-1"), 25L, false
                ))
        );

        assertEquals(List.of("mmarco:zh:p-2", "mmarco:zh:p-1"), run.replays().get(0).rankedChunkIds());
        assertEquals(Set.of("mmarco:zh:p-1"), run.replays().get(0).goldChunkIds());
        assertEquals(25L, run.replays().get(0).latencyMs());
    }

    @Test
    void rejectsUnknownRuntimeChunkUuidAndQuerySetDrift() {
        MmarcoZhSampledReplayCollector collector = new MmarcoZhSampledReplayCollector();

        IllegalStateException unknownChunk = assertThrows(IllegalStateException.class, () -> collector.collect(
                "rrf-only", fingerprint(),
                List.of(new MmarcoZhSampledDatasetFreezer.Query("q-1", "query one")),
                Map.of("q-1", List.of("mmarco:zh:p-1")),
                Map.of("uuid-p-1", "mmarco:zh:p-1"),
                List.of(new MmarcoZhSampledReplayCollector.RuntimeQueryResult(
                        "q-1", List.of("unknown"), 25L, false
                ))
        ));
        assertTrue(unknownChunk.getMessage().contains("UUID"));

        IllegalStateException queryDrift = assertThrows(IllegalStateException.class, () -> collector.collect(
                "rrf-only", fingerprint(),
                List.of(new MmarcoZhSampledDatasetFreezer.Query("q-1", "query one")),
                Map.of("q-1", List.of("mmarco:zh:p-1")),
                Map.of("uuid-p-1", "mmarco:zh:p-1"),
                List.of(new MmarcoZhSampledReplayCollector.RuntimeQueryResult(
                        "q-other", List.of("uuid-p-1"), 25L, false
                ))
        ));
        assertTrue(queryDrift.getMessage().contains("query"));
    }

    private MmarcoZhSampledEvaluator.EvaluationFingerprint fingerprint() {
        return new MmarcoZhSampledEvaluator.EvaluationFingerprint(
                "mmarco-zh-sampled-v1",
                "source-sha",
                "manifest-sha",
                "mmarco-zh-deterministic-uuid-v1",
                "vchord-bm25-v1",
                "bge-m3",
                "bm25-dictionary-v1",
                "rrf-k-60",
                10,
                50,
                "query-set-sha"
        );
    }
}
