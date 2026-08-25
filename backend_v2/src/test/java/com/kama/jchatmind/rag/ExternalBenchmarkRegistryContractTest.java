package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalBenchmarkRegistryContractTest {

    private static final Path REGISTRY_PATH = Path.of("..", "rag-eval", "external-benchmark-registry.json");

    @Test
    void recordsVersionedOfficialSourcesWithoutTreatingUndownloadedDataAsVerified() throws Exception {
        assertTrue(Files.exists(REGISTRY_PATH));

        JsonNode root = new ObjectMapper().readTree(Files.readString(REGISTRY_PATH));
        JsonNode mMarco = root.path("benchmarks").path("mmarco");
        JsonNode crudRag = root.path("benchmarks").path("crudRag");

        assertEquals("Apache-2.0", mMarco.path("license").path("spdx").asText());
        assertEquals("6d039c4638c0ba3e46a9cb7b498b145e7edc6230", mMarco.path("datasetRevision").asText());
        assertEquals("not_downloaded", mMarco.path("artifacts").path("status").asText());
        assertTrue(mMarco.path("artifacts").path("sha256").isNull());

        assertEquals("1aace383994e1f68efa12cf2a8e2dadfb4102ceb", crudRag.path("repositoryRevision").asText());
        assertEquals("unresolved", crudRag.path("license").path("status").asText());
        assertEquals("blocked", crudRag.path("redistribution").path("status").asText());
        assertFalse(crudRag.path("artifacts").path("sha256").isTextual());
        assertTrue(containsArtifact(crudRag, "data/crud/CRUD_Data.zip"));
        assertTrue(containsArtifact(crudRag, "data/crud/merged.zip"));
        assertFalse(containsArtifact(crudRag, "data/crud/merged.json"));
    }

    private boolean containsArtifact(JsonNode benchmark, String artifact) {
        for (JsonNode candidate : benchmark.path("artifacts").path("requiredArtifacts")) {
            if (artifact.equals(candidate.asText())) {
                return true;
            }
        }
        return false;
    }
}
