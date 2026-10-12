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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LongMemEvalSourcePinContractTest {

    private static final String PIN_PATH = "memory-eval/longmemeval-source-pin-v1.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void pinsOfficialSourceAndDisjointThirtyCaseSamplingPlan() throws Exception {
        JsonNode pin = loadPin();
        JsonNode source = pin.path("source");
        JsonNode subset = pin.path("projectSubset");
        JsonNode categories = subset.path("categories");

        assertThat(pin.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(pin.path("datasetId").asText()).isEqualTo("longmemeval-s-cleaned-2025-09");
        assertThat(source.path("revision").asText())
                .matches("[0-9a-f]{40}");
        assertThat(source.path("license").asText()).isEqualTo("MIT");
        assertThat(source.path("sizeBytes").asLong()).isEqualTo(277_383_467L);
        assertThat(source.path("sha256").asText()).matches("[0-9a-f]{64}");
        assertThat(source.path("recordCount").asInt()).isEqualTo(500);
        assertThat(source.path("officialTrainTestSplit").asBoolean()).isFalse();
        JsonNode observedSchema = source.path("observedSchema");
        assertThat(observedSchema.path("abstentionCount").asInt()).isEqualTo(30);
        assertThat(observedSchema.path("questionTypeCounts").path("single-session-user").asInt()).isEqualTo(70);
        assertThat(observedSchema.path("questionTypeCounts").path("single-session-assistant").asInt()).isEqualTo(56);
        assertThat(observedSchema.path("questionTypeCounts").path("single-session-preference").asInt()).isEqualTo(30);
        assertThat(observedSchema.path("questionTypeCounts").path("multi-session").asInt()).isEqualTo(133);
        assertThat(observedSchema.path("questionTypeCounts").path("knowledge-update").asInt()).isEqualTo(78);
        assertThat(observedSchema.path("questionTypeCounts").path("temporal-reasoning").asInt()).isEqualTo(133);
        assertThat(observedSchema.path("repeatedSessionIdOccurrences").asInt()).isEqualTo(13);
        assertThat(observedSchema.path("repeatedSessionIdDateDifferences").asInt()).isEqualTo(13);
        assertThat(observedSchema.path("repeatedSessionIdPayloadDifferences").asInt()).isZero();
        assertThat(subset.path("datasetId").asText()).isEqualTo("longmemeval-30-v1");
        assertThat(subset.path("status").asText()).isEqualTo("candidate");
        assertThat(subset.path("reviewStatus").asText()).isEqualTo("pending");
        assertThat(subset.path("selectionSeed").asInt()).isEqualTo(20_261_009);
        JsonNode evaluator = pin.path("officialEvaluator");
        assertThat(evaluator.path("revision").asText()).matches("[0-9a-f]{40}");
        assertThat(evaluator.path("sizeBytes").asLong()).isEqualTo(7436);
        assertThat(evaluator.path("sha256").asText()).matches("[0-9a-f]{64}");
        assertThat(evaluator.path("status").asText()).isEqualTo("pinned_not_executed");
        assertThat(pin.has("cases")).isFalse();

        int total = 0;
        Set<String> questionTypes = new HashSet<>();
        for (JsonNode category : categories) {
            int count = category.path("count").asInt();
            total += count;
            if ("abstention".equals(category.path("id").asText())) {
                assertThat(category.path("questionIdSuffix").asText()).isEqualTo("_abs");
                assertThat(category.path("excludeFromOtherCategories").asBoolean()).isTrue();
                continue;
            }
            JsonNode types = category.path("questionTypes");
            assertThat(types.isArray()).isTrue();
            assertThat(types.size() * category.path("countPerQuestionType").asInt()).isEqualTo(count);
            for (JsonNode type : types) {
                assertThat(questionTypes.add(type.asText()))
                        .as("official questionType %s appears in only one project category", type.asText())
                        .isTrue();
            }
        }
        assertThat(categories).hasSize(5);
        assertThat(total).isEqualTo(subset.path("caseCount").asInt()).isEqualTo(30);
    }

    @Test
    @EnabledIfSystemProperty(named = "g3.longmemeval.source.enabled", matches = "true")
    void downloadedSourcesMatchPinnedSizeAndSha256() throws Exception {
        JsonNode pin = loadPin();
        JsonNode source = pin.path("source");
        Path sourcePath = Path.of(source.path("localPath").asText());

        assertThat(Files.exists(sourcePath)).isTrue();
        assertThat(Files.size(sourcePath)).isEqualTo(source.path("sizeBytes").asLong());
        assertThat(sha256(sourcePath)).isEqualTo(source.path("sha256").asText());

        JsonNode evaluator = pin.path("officialEvaluator");
        Path evaluatorPath = Path.of(evaluator.path("localPath").asText());
        assertThat(Files.exists(evaluatorPath)).isTrue();
        assertThat(Files.size(evaluatorPath)).isEqualTo(evaluator.path("sizeBytes").asLong());
        assertThat(sha256(evaluatorPath)).isEqualTo(evaluator.path("sha256").asText());
    }

    private JsonNode loadPin() throws Exception {
        try (InputStream inputStream = new ClassPathResource(PIN_PATH).getInputStream()) {
            return OBJECT_MAPPER.readTree(inputStream);
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream inputStream = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
