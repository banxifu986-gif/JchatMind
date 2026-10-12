package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LongMemEval30CandidateFreezerTest {

    private static final String PIN_PATH = "memory-eval/longmemeval-source-pin-v1.json";
    private static final Path MANIFEST_PATH = Path.of(
            "target", "memory-eval", "longmemeval-30-v1", "longmemeval-30-v1.candidate.json"
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @EnabledIfSystemProperty(named = "g3.longmemeval.source.enabled", matches = "true")
    void writesAndReusesDeterministicPendingManifestWithSessionOccurrences() throws Exception {
        JsonNode pin = loadPin();
        Path sourcePath = Path.of(pin.path("source").path("localPath").asText());

        LongMemEval30CandidateFreezer.writeCandidateManifest(pin, sourcePath, MANIFEST_PATH);
        byte[] firstBytes = Files.readAllBytes(MANIFEST_PATH);
        String firstHash = Files.readString(sidecarPath()).trim();
        LongMemEval30CandidateFreezer.writeCandidateManifest(pin, sourcePath, MANIFEST_PATH);
        byte[] secondBytes = Files.readAllBytes(MANIFEST_PATH);

        JsonNode manifest = OBJECT_MAPPER.readTree(firstBytes);
        assertThat(secondBytes).containsExactly(firstBytes);
        assertThat(firstHash).isEqualTo(sha256(firstBytes));
        assertThat(manifest.path("datasetId").asText()).isEqualTo("longmemeval-30-v1");
        assertThat(manifest.path("status").asText()).isEqualTo("candidate");
        assertThat(manifest.path("releaseEligible").asBoolean()).isFalse();
        assertThat(manifest.path("reviewStatus").asText()).isEqualTo("pending");
        assertThat(manifest.path("cases")).hasSize(30);
        assertThat(manifest.path("source").path("sha256").asText())
                .isEqualTo(pin.path("source").path("sha256").asText());
        assertThat(manifest.path("officialEvaluator").path("status").asText())
                .isEqualTo("pinned_not_executed");

        Map<String, Long> casesByCategory = new HashMap<>();
        for (JsonNode candidateCase : manifest.path("cases")) {
            String caseId = candidateCase.path("caseId").asText();
            JsonNode sourceRecord = candidateCase.path("sourceRecord");
            JsonNode sessions = sourceRecord.path("haystack_sessions");
            JsonNode sessionIds = sourceRecord.path("haystack_session_ids");
            JsonNode dates = sourceRecord.path("haystack_dates");
            JsonNode occurrences = candidateCase.path("sessionOccurrences");
            JsonNode evidenceRefs = candidateCase.path("evidenceMessageRefs");

            assertThat(sourceRecord.path("question_id").asText()).isEqualTo(caseId);
            assertThat(candidateCase.path("reviewStatus").asText()).isEqualTo("pending");
            assertThat(candidateCase.path("sourceRecordSha256").asText()).matches("[0-9a-f]{64}");
            assertThat(occurrences).hasSize(sessions.size());
            for (int index = 0; index < occurrences.size(); index++) {
                JsonNode occurrence = occurrences.get(index);
                assertThat(occurrence.path("occurrenceIndex").asInt()).isEqualTo(index);
                assertThat(occurrence.path("sourceSessionId").asText()).isEqualTo(sessionIds.get(index).asText());
                assertThat(occurrence.path("date").asText()).isEqualTo(dates.get(index).asText());
                assertThat(occurrence.path("occurrenceKey").asText()).isEqualTo(caseId + "#" + index);
            }
            int evidenceRefIndex = 0;
            for (int sessionIndex = 0; sessionIndex < sessions.size(); sessionIndex++) {
                JsonNode turns = sessions.get(sessionIndex);
                for (int messageIndex = 0; messageIndex < turns.size(); messageIndex++) {
                    if (turns.get(messageIndex).path("has_answer").asBoolean(false)) {
                        JsonNode evidenceRef = evidenceRefs.get(evidenceRefIndex++);
                        assertThat(evidenceRef.path("occurrenceIndex").asInt()).isEqualTo(sessionIndex);
                        assertThat(evidenceRef.path("sourceSessionId").asText())
                                .isEqualTo(sessionIds.get(sessionIndex).asText());
                        assertThat(evidenceRef.path("messageIndex").asInt()).isEqualTo(messageIndex);
                    }
                }
            }
            assertThat(evidenceRefIndex).isEqualTo(evidenceRefs.size());
            assertThat(sourceRecord.path("answer_session_ids").isArray()).isTrue();
            casesByCategory.merge(candidateCase.path("categoryId").asText(), 1L, Long::sum);
        }
        assertThat(casesByCategory).containsExactlyInAnyOrderEntriesOf(Map.of(
                "information_extraction", 6L,
                "multi_session_reasoning", 6L,
                "knowledge_update", 6L,
                "temporal_reasoning", 6L,
                "abstention", 6L
        ));
    }

    private JsonNode loadPin() throws Exception {
        try (InputStream inputStream = new ClassPathResource(PIN_PATH).getInputStream()) {
            return OBJECT_MAPPER.readTree(inputStream);
        }
    }

    private Path sidecarPath() {
        return MANIFEST_PATH.resolveSibling(MANIFEST_PATH.getFileName() + ".sha256");
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
