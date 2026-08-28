package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmarcoZhSampledReportWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesFrozenInputsArmMetricsAndBootstrapComparison() throws Exception {
        MmarcoZhSampledEvaluator evaluator = new MmarcoZhSampledEvaluator();
        MmarcoZhSampledEvaluator.EvaluationFingerprint fingerprint = new MmarcoZhSampledEvaluator.EvaluationFingerprint(
                "mmarco-zh-sampled-v1",
                "source-sha",
                "candidate-manifest-sha",
                "mmarco-zh-passage-id-v1",
                "vchord-bm25-v1",
                "bge-m3",
                "bm25-dictionary-v1",
                "config-sha",
                10,
                50,
                "query-set-sha"
        );
        MmarcoZhSampledEvaluator.VariantRun local = new MmarcoZhSampledEvaluator.VariantRun(
                "local-rule-rerank",
                fingerprint,
                List.of(replay("q-1", List.of("mmarco:zh:1", "noise"), 10))
        );
        MmarcoZhSampledEvaluator.VariantRun tei = new MmarcoZhSampledEvaluator.VariantRun(
                "tei-bge-rerank",
                fingerprint,
                List.of(replay("q-1", List.of("mmarco:zh:1", "noise"), 11))
        );
        Path reportPath = temporaryDirectory.resolve("mmarco-zh-sampled-v1-retrieval-ab.json");

        new MmarcoZhSampledReportWriter().write(
                reportPath,
                "mmarco-zh-sampled-v1-ab-001",
                "BAAI/bge-reranker-v2-m3",
                List.of(local, tei),
                evaluator.compare(local, tei)
        );

        JsonNode root = new ObjectMapper().readTree(reportPath.toFile());
        assertEquals("mmarco-zh-sampled-v1-ab-001", root.path("runId").asText());
        assertEquals("mmarco-zh-sampled-v1", root.path("datasetVersion").asText());
        assertEquals("source-sha", root.path("sourceSha256").asText());
        assertEquals("candidate-manifest-sha", root.path("candidateManifestSha256").asText());
        assertEquals("mmarco-zh-passage-id-v1", root.path("mappingVersion").asText());
        assertEquals("config-sha", root.path("configSha256").asText());
        assertEquals("BAAI/bge-reranker-v2-m3", root.path("rerankerModel").asText());
        assertEquals(1, root.path("sampleSize").asInt());
        assertEquals(2, root.path("runs").size());
        assertEquals(1, root.path("runs").get(1).path("validCount").asInt());
        assertEquals(0, root.path("runs").get(1).path("invalidCount").asInt());
        assertEquals(11, root.path("runs").get(1).path("metrics").path("p95LatencyMs").asLong());
        assertEquals("id_based", root.path("runs").get(1).path("ragas").path("status").asText());
        assertEquals(1D, root.path("runs").get(1).path("ragas").path("idBasedContextPrecisionAt10").asDouble(), 0.0001D);
        assertEquals(1D, root.path("runs").get(1).path("ragas").path("idBasedContextRecallAt10").asDouble(), 0.0001D);
        assertEquals("q-1", root.path("runs").get(1).path("queryReplays").get(0).path("queryId").asText());
        assertEquals("mmarco:zh:1", root.path("runs").get(1).path("queryReplays").get(0)
                .path("rankedChunkIds").get(0).asText());
        assertEquals(1_000, root.path("comparison").path("bootstrap").path("samples").asInt());
        assertTrue(root.path("comparison").path("status").asText().contains("eligible"));
    }

    private MmarcoZhSampledEvaluator.QueryReplay replay(String queryId, List<String> rankedChunkIds, long latencyMs) {
        return new MmarcoZhSampledEvaluator.QueryReplay(
                queryId,
                Set.of("mmarco:zh:1"),
                rankedChunkIds,
                latencyMs,
                false
        );
    }
}
