package com.kama.jchatmind.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class LongMemEvalOfficialDatasetLoaderTest {

    private static final String PIN_PATH = "memory-eval/longmemeval-source-pin-v1.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @EnabledIfSystemProperty(named = "g3.longmemeval.source.enabled", matches = "true")
    void validatesPinnedOfficialRecordSchemaWithoutExposingQuestionContent() throws Exception {
        JsonNode sourcePin = loadSourcePin().path("source");
        Path sourcePath = Path.of(sourcePin.path("localPath").asText());

        LongMemEvalOfficialDatasetLoader.SourceSummary summary =
                LongMemEvalOfficialDatasetLoader.validateSource(sourcePath, sourcePin);
        JsonNode observedSchema = sourcePin.path("observedSchema");

        assertThat(summary.recordCount()).isEqualTo(sourcePin.path("recordCount").asInt());
        assertThat(summary.abstentionCount()).isEqualTo(observedSchema.path("abstentionCount").asInt());
        assertThat(summary.sizeBytes()).isEqualTo(sourcePin.path("sizeBytes").asLong());
        assertThat(summary.sha256()).isEqualTo(sourcePin.path("sha256").asText());
        Map<String, Integer> expectedQuestionTypeCounts = OBJECT_MAPPER.convertValue(
                observedSchema.path("questionTypeCounts"), new TypeReference<>() { }
        );
        assertThat(summary.questionTypeCounts()).isEqualTo(expectedQuestionTypeCounts);
        assertThat(summary.repeatedSessionIdCount())
                .isEqualTo(observedSchema.path("repeatedSessionIdOccurrences").asInt());
        assertThat(summary.repeatedSessionDateDifferenceCount())
                .isEqualTo(observedSchema.path("repeatedSessionIdDateDifferences").asInt());
        assertThat(summary.repeatedSessionPayloadDifferenceCount())
                .isEqualTo(observedSchema.path("repeatedSessionIdPayloadDifferences").asInt());
        assertThat(summary.caseReferences()).hasSize(500);
        assertThat(summary.caseReferences().stream()
                .map(LongMemEvalOfficialDatasetLoader.SourceReference::questionId)
                .distinct()).hasSize(500);
        assertThat(summary.caseReferences().stream()
                .filter(LongMemEvalOfficialDatasetLoader.SourceReference::abstention))
                .hasSize(30);
        assertThat(summary.caseReferences().stream()
                .map(LongMemEvalOfficialDatasetLoader.SourceReference::sourceIndex))
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 500).boxed().toList());
        assertThat(summary.questionTypeCounts().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(summary.recordCount());
    }

    private JsonNode loadSourcePin() throws Exception {
        try (InputStream inputStream = new ClassPathResource(PIN_PATH).getInputStream()) {
            return OBJECT_MAPPER.readTree(inputStream);
        }
    }
}
