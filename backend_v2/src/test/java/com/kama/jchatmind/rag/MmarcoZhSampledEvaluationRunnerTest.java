package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MmarcoZhSampledEvaluationRunnerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsThreeFrozenArmsAndWritesOneComparableReport() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
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
        Path rrfOnlyPath = writeRun(objectMapper, "rrf-only", fingerprint, 10);
        Path localRulePath = writeRun(objectMapper, "local-rule-rerank", fingerprint, 11);
        Path teiPath = writeRun(objectMapper, "tei-bge-rerank", fingerprint, 12);
        Path reportPath = temporaryDirectory.resolve("retrieval-ab.json");

        new MmarcoZhSampledEvaluationRunner().evaluateAndWrite(
                rrfOnlyPath,
                localRulePath,
                teiPath,
                reportPath,
                "mmarco-zh-sampled-v1-ab-001",
                "BAAI/bge-reranker-v2-m3"
        );

        JsonNode report = objectMapper.readTree(reportPath.toFile());
        assertEquals(3, report.path("runs").size());
        assertEquals("rrf-only", report.path("runs").get(0).path("variant").asText());
        assertEquals("local-rule-rerank", report.path("runs").get(1).path("variant").asText());
        assertEquals("tei-bge-rerank", report.path("runs").get(2).path("variant").asText());
        assertEquals("eligible_for_full_validation", report.path("comparison").path("status").asText());
    }

    private Path writeRun(
            ObjectMapper objectMapper,
            String variant,
            MmarcoZhSampledEvaluator.EvaluationFingerprint fingerprint,
            long latencyMs
    ) throws Exception {
        MmarcoZhSampledEvaluator.VariantRun run = new MmarcoZhSampledEvaluator.VariantRun(
                variant,
                fingerprint,
                List.of(new MmarcoZhSampledEvaluator.QueryReplay(
                        "q-1",
                        Set.of("mmarco:zh:1"),
                        List.of("mmarco:zh:1", "noise"),
                        latencyMs,
                        false
                ))
        );
        Path path = temporaryDirectory.resolve(variant + ".json");
        objectMapper.writeValue(path.toFile(), run);
        return path;
    }
}
