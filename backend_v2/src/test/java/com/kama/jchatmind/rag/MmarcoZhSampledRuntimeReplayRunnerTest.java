package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmarcoZhSampledRuntimeReplayRunnerTest {

    private final MmarcoZhSampledRuntimeReplayRunner runner = new MmarcoZhSampledRuntimeReplayRunner();

    @Test
    void recordsRuntimeUuidOrderLatencyAndTeiFallbackForEveryFrozenQuery() {
        List<MmarcoZhSampledDatasetFreezer.Query> queries = List.of(
                new MmarcoZhSampledDatasetFreezer.Query("q-1", "first query"),
                new MmarcoZhSampledDatasetFreezer.Query("q-2", "second query")
        );

        List<MmarcoZhSampledReplayCollector.RuntimeQueryResult> results = runner.run(
                queries,
                query -> new MmarcoZhSampledRuntimeReplayRunner.RetrievalOutcome(
                        Map.of(
                                "q-1", List.of("uuid-1", "uuid-2"),
                                "q-2", List.of("uuid-3")
                        ).get(query.id()),
                        "q-2".equals(query.id())
                )
        );

        assertEquals(List.of("q-1", "q-2"), results.stream()
                .map(MmarcoZhSampledReplayCollector.RuntimeQueryResult::queryId)
                .toList());
        assertEquals(List.of("uuid-1", "uuid-2"), results.get(0).runtimeChunkUuids());
        assertTrue(results.get(0).latencyMs() >= 0L);
        assertTrue(results.get(1).teiFallback());
    }

    @Test
    void rejectsDuplicateRuntimeUuidBeforeTheReplayIsCollected() {
        List<MmarcoZhSampledDatasetFreezer.Query> queries = List.of(
                new MmarcoZhSampledDatasetFreezer.Query("q-1", "first query")
        );

        assertThrows(IllegalStateException.class, () -> runner.run(
                queries,
                query -> new MmarcoZhSampledRuntimeReplayRunner.RetrievalOutcome(
                        List.of("uuid-1", "uuid-1"),
                        false
                )
        ));
    }
}
