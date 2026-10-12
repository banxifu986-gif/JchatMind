package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongMemEvalCandidateManifestLoaderTest {

    private static final String PIN_PATH = "memory-eval/longmemeval-source-pin-v1.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void rejectsManifestWhenSha256SidecarDoesNotMatch() throws Exception {
        JsonNode pin = loadPin();
        Path manifestPath = temporaryDirectory.resolve("candidate.json");
        Files.writeString(manifestPath, "{\"candidate\":true}");
        Files.writeString(sidecarPath(manifestPath), "0".repeat(64) + System.lineSeparator());

        assertThatThrownBy(() -> LongMemEvalCandidateManifestLoader.load(manifestPath, pin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    @EnabledIfSystemProperty(named = "g3.longmemeval.source.enabled", matches = "true")
    void loadsGeneratedCandidateOnlyAfterVerifyingAllPinnedHashes() throws Exception {
        JsonNode pin = loadPin();
        Path manifestPath = Path.of(
                "target", "memory-eval", "longmemeval-30-v1", "longmemeval-30-v1.candidate.json"
        );

        LongMemEvalCandidateManifestLoader.VerifiedManifest verified =
                LongMemEvalCandidateManifestLoader.load(manifestPath, pin);

        assertThat(verified.manifestSha256()).matches("[0-9a-f]{64}");
        assertThat(verified.caseSha256()).matches("[0-9a-f]{64}");
        assertThat(verified.manifest().path("status").asText()).isEqualTo("candidate");
        assertThat(verified.manifest().path("reviewStatus").asText()).isEqualTo("pending");
        assertThat(verified.manifest().path("releaseEligible").asBoolean()).isFalse();
        assertThat(verified.manifest().path("cases")).hasSize(30);
    }

    @TempDir
    Path temporaryDirectory;

    private Path sidecarPath(Path manifestPath) {
        return manifestPath.resolveSibling(manifestPath.getFileName() + ".sha256");
    }

    private JsonNode loadPin() throws Exception {
        try (InputStream inputStream = new ClassPathResource(PIN_PATH).getInputStream()) {
            return OBJECT_MAPPER.readTree(inputStream);
        }
    }
}
