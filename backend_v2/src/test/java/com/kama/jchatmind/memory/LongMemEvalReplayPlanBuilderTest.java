package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongMemEvalReplayPlanBuilderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void preservesRepeatedSessionOccurrencesAndSeparatesTargetsFromHistory() throws Exception {
        JsonNode manifest = candidateManifest();

        LongMemEvalReplayPlanBuilder.ReplayPlan plan = LongMemEvalReplayPlanBuilder.build(manifest);

        assertThat(plan.datasetId()).isEqualTo("longmemeval-30-v1");
        assertThat(plan.cases()).hasSize(1);
        LongMemEvalReplayPlanBuilder.ReplayCase replayCase = plan.cases().get(0);
        assertThat(replayCase.caseId()).isEqualTo("case-1");
        assertThat(replayCase.categoryId()).isEqualTo("information_extraction");
        assertThat(replayCase.target().question()).isEqualTo("target question");
        assertThat(replayCase.target().answer().asText()).isEqualTo("gold answer");
        assertThat(replayCase.gold().answerSessionIds()).containsExactly("session-reused");
        assertThat(replayCase.gold().shouldAbstain()).isFalse();
        assertThat(replayCase.sessions()).hasSize(2);
        assertThat(replayCase.sessions().get(0).occurrenceIndex()).isZero();
        assertThat(replayCase.sessions().get(1).occurrenceIndex()).isEqualTo(1);
        assertThat(replayCase.sessions())
                .extracting(LongMemEvalReplayPlanBuilder.ReplaySession::sourceSessionId)
                .containsExactly("session-reused", "session-reused");
        assertThat(replayCase.sessions())
                .extracting(LongMemEvalReplayPlanBuilder.ReplaySession::date)
                .containsExactly("2024-01-01T09:00:00", "2024-01-02T09:00:00");
        assertThat(replayCase.sessions().get(0).messages())
                .containsExactly(new LongMemEvalReplayPlanBuilder.ReplayMessage(0, "user", "prefers tea"));
        assertThat(replayCase.sessions().get(1).messages())
                .containsExactly(new LongMemEvalReplayPlanBuilder.ReplayMessage(0, "assistant", "noted"));
        assertThat(replayCase.gold().evidenceMessageRefs()).containsExactly(
                new LongMemEvalReplayPlanBuilder.EvidenceMessageRef(0, "session-reused", 0)
        );
        assertThat(replayCase.sessions().toString())
                .doesNotContain("has_answer", "target question", "gold answer");
    }

    @Test
    void rejectsSessionOccurrenceMetadataThatDoesNotMatchSourceArrays() throws Exception {
        ObjectNode manifest = (ObjectNode) candidateManifest();
        ObjectNode candidateCase = (ObjectNode) manifest.path("cases").get(0);
        ArrayNode occurrences = (ArrayNode) candidateCase.path("sessionOccurrences");
        ((ObjectNode) occurrences.get(1)).put("occurrenceIndex", 0);

        assertThatThrownBy(() -> LongMemEvalReplayPlanBuilder.build(manifest))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @EnabledIfSystemProperty(named = "g3.longmemeval.source.enabled", matches = "true")
    void buildsReplayPlanFromVerifiedTargetCandidateManifest() throws Exception {
        JsonNode pin = loadPin();
        Path manifestPath = Path.of(
                "target", "memory-eval", "longmemeval-30-v1", "longmemeval-30-v1.candidate.json"
        );
        LongMemEvalCandidateManifestLoader.VerifiedManifest verified =
                LongMemEvalCandidateManifestLoader.load(manifestPath, pin);

        LongMemEvalReplayPlanBuilder.ReplayPlan plan =
                LongMemEvalReplayPlanBuilder.build(verified.manifest());

        assertThat(plan.cases()).hasSize(30);
        assertThat(plan.cases()).allSatisfy(replayCase -> {
            assertThat(replayCase.sessions()).isNotEmpty();
            assertThat(replayCase.target().question()).isNotBlank();
            assertThat(replayCase.target().answer()).isNotNull();
            for (LongMemEvalReplayPlanBuilder.ReplaySession session : replayCase.sessions()) {
                for (LongMemEvalReplayPlanBuilder.ReplayMessage message : session.messages()) {
                    JsonNode replayMessage = OBJECT_MAPPER.valueToTree(message);
                    assertThat(replayMessage.size()).isEqualTo(3);
                    assertThat(replayMessage.has("has_answer")).isFalse();
                    assertThat(replayMessage.has("question")).isFalse();
                    assertThat(replayMessage.has("answer")).isFalse();
                }
            }
        });
    }

    private JsonNode candidateManifest() {
        ObjectNode sourceRecord = OBJECT_MAPPER.createObjectNode();
        sourceRecord.put("question_id", "case-1");
        sourceRecord.put("question_type", "single-session-user");
        sourceRecord.put("question", "target question");
        sourceRecord.put("answer", "gold answer");
        sourceRecord.put("question_date", "2024-01-03T09:00:00");
        sourceRecord.putArray("answer_session_ids").add("session-reused");

        ArrayNode sessionIds = sourceRecord.putArray("haystack_session_ids");
        sessionIds.add("session-reused");
        sessionIds.add("session-reused");
        ArrayNode dates = sourceRecord.putArray("haystack_dates");
        dates.add("2024-01-01T09:00:00");
        dates.add("2024-01-02T09:00:00");
        ArrayNode sessions = sourceRecord.putArray("haystack_sessions");
        sessions.addArray().add(turn("user", "prefers tea", true));
        sessions.addArray().add(turn("assistant", "noted", false));

        ObjectNode candidateCase = OBJECT_MAPPER.createObjectNode();
        candidateCase.put("caseId", "case-1");
        candidateCase.put("categoryId", "information_extraction");
        candidateCase.put("reviewStatus", "pending");
        candidateCase.set("sourceRecord", sourceRecord);
        ArrayNode occurrences = candidateCase.putArray("sessionOccurrences");
        occurrences.addObject()
                .put("occurrenceIndex", 0)
                .put("occurrenceKey", "case-1#0")
                .put("sourceSessionId", "session-reused")
                .put("date", "2024-01-01T09:00:00");
        occurrences.addObject()
                .put("occurrenceIndex", 1)
                .put("occurrenceKey", "case-1#1")
                .put("sourceSessionId", "session-reused")
                .put("date", "2024-01-02T09:00:00");
        ArrayNode evidenceRefs = candidateCase.putArray("evidenceMessageRefs");
        evidenceRefs.addObject()
                .put("occurrenceIndex", 0)
                .put("sourceSessionId", "session-reused")
                .put("messageIndex", 0);

        ObjectNode manifest = OBJECT_MAPPER.createObjectNode();
        manifest.put("datasetId", "longmemeval-30-v1");
        manifest.put("status", "candidate");
        manifest.put("releaseEligible", false);
        manifest.put("reviewStatus", "pending");
        manifest.put("caseCount", 1);
        manifest.putArray("cases").add(candidateCase);
        return manifest;
    }

    private JsonNode loadPin() throws Exception {
        try (InputStream inputStream = new ClassPathResource(
                "memory-eval/longmemeval-source-pin-v1.json"
        ).getInputStream()) {
            return OBJECT_MAPPER.readTree(inputStream);
        }
    }

    private ObjectNode turn(String role, String content, boolean hasAnswer) {
        ObjectNode turn = OBJECT_MAPPER.createObjectNode();
        turn.put("role", role);
        turn.put("content", content);
        turn.put("has_answer", hasAnswer);
        return turn;
    }
}
