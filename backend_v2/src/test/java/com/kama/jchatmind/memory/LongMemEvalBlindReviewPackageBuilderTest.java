package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongMemEvalBlindReviewPackageBuilderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final UUID BATCH_ID = UUID.fromString("19fcd271-75d1-448d-9426-53f84a947ef1");
    private static final Set<String> VERIFIED_CASE_IDS = Set.of("case-1", "case-2");

    @Test
    void reviewerPacketContainsOnlyCandidateAndEvidenceWhilePrivateMapRetainsLinks() throws Exception {
        LongMemEvalBlindReviewPackageBuilder.Artifacts artifacts =
                LongMemEvalBlindReviewPackageBuilder.build(
                        "longmemeval-30-v1",
                        BATCH_ID,
                        VERIFIED_CASE_IDS,
                        candidates()
                );
        JsonNode packet = artifacts.reviewerPacket();
        JsonNode privateMap = artifacts.privateMapping();

        assertThat(packet.path("datasetId").asText()).isEqualTo("longmemeval-30-v1");
        assertThat(packet.path("batchId").asText()).isEqualTo(BATCH_ID.toString());
        assertThat(packet.path("items")).hasSize(2);
        assertThat(privateMap.path("items")).hasSize(2);
        for (JsonNode item : packet.path("items")) {
            String reviewItemId = item.path("reviewItemId").asText();
            assertThat(reviewItemId).matches("[0-9a-f-]{36}");
            assertThat(item.path("content").asText()).isNotBlank();
            assertThat(item.path("evidenceText").asText()).isNotBlank();
            assertThat(item.path("decision").isNull()).isTrue();
            assertThat(item.has("caseId")).isFalse();
            assertThat(item.has("candidateId")).isFalse();
            assertThat(item.has("evidenceMessageId")).isFalse();
            assertThat(item.has("question")).isFalse();
            assertThat(item.has("answer")).isFalse();
            assertThat(item.has("questionType")).isFalse();
            assertThat(item.has("gold")).isFalse();

            JsonNode mapping = findByText(privateMap.path("items"), "reviewItemId", reviewItemId);
            assertThat(mapping.path("caseId").asText()).isIn(VERIFIED_CASE_IDS);
            assertThat(mapping.path("candidateId").asText()).isNotBlank();
            assertThat(mapping.path("evidenceMessageId").asText()).isNotBlank();
        }

        String publicJson = OBJECT_MAPPER.writeValueAsString(packet);
        assertThat(publicJson).doesNotContain("target question", "gold answer", "candidate-1", "message-1");
    }

    @Test
    void producesStableOpaqueIdsForTheSameBatchWithoutUsingCaseIdsInPacket() throws Exception {
        LongMemEvalBlindReviewPackageBuilder.Artifacts first =
                LongMemEvalBlindReviewPackageBuilder.build(
                        "longmemeval-30-v1", BATCH_ID, VERIFIED_CASE_IDS, candidates()
                );
        LongMemEvalBlindReviewPackageBuilder.Artifacts second =
                LongMemEvalBlindReviewPackageBuilder.build(
                        "longmemeval-30-v1", BATCH_ID, VERIFIED_CASE_IDS, candidates()
                );

        assertThat(second.reviewerPacket()).isEqualTo(first.reviewerPacket());
        assertThat(second.privateMapping()).isEqualTo(first.privateMapping());
    }

    @Test
    void rejectsCandidatesOutsideVerifiedManifestOrOutsidePendingState() {
        LongMemEvalBlindReviewPackageBuilder.CandidateForReview outsideCase =
                candidate("case-outside", "candidate-3", "PREFERENCE", "prefers tea", "msg-3");
        LongMemEvalBlindReviewPackageBuilder.CandidateForReview persisted =
                new LongMemEvalBlindReviewPackageBuilder.CandidateForReview(
                        "case-1", "candidate-persisted", "FACT", "works in a lab", "high",
                        "msg-4", "The user said they work in a lab.", "PERSISTED"
                );

        assertThatThrownBy(() -> LongMemEvalBlindReviewPackageBuilder.build(
                "longmemeval-30-v1", BATCH_ID, VERIFIED_CASE_IDS, List.of(outsideCase)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LongMemEvalBlindReviewPackageBuilder.build(
                "longmemeval-30-v1", BATCH_ID, VERIFIED_CASE_IDS, List.of(persisted)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private List<LongMemEvalBlindReviewPackageBuilder.CandidateForReview> candidates() {
        return List.of(
                candidate("case-1", "candidate-1", "PREFERENCE", "prefers tea", "message-1"),
                candidate("case-2", "candidate-2", "CONSTRAINT", "avoids peanuts", "message-2")
        );
    }

    private LongMemEvalBlindReviewPackageBuilder.CandidateForReview candidate(
            String caseId,
            String candidateId,
            String memoryType,
            String content,
            String evidenceMessageId
    ) {
        return new LongMemEvalBlindReviewPackageBuilder.CandidateForReview(
                caseId,
                candidateId,
                memoryType,
                content,
                "medium",
                evidenceMessageId,
                "The user stated: " + content + ".",
                "PENDING"
        );
    }

    private JsonNode findByText(JsonNode array, String field, String expected) {
        for (JsonNode item : array) {
            if (expected.equals(item.path(field).asText())) {
                return item;
            }
        }
        throw new AssertionError("找不到 " + field + "=" + expected);
    }
}
