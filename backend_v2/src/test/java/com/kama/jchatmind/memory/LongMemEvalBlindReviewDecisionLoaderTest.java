package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongMemEvalBlindReviewDecisionLoaderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final UUID BATCH_ID = UUID.fromString("19fcd271-75d1-448d-9426-53f84a947ef1");

    @Test
    void resolvesCompleteBlindDecisionsThroughPrivateMapping() throws Exception {
        LongMemEvalBlindReviewPackageBuilder.Artifacts artifacts = artifacts();
        JsonNode privateMap = artifacts.privateMapping();
        JsonNode decisionFile = decisionFile(artifacts, true);

        List<LongMemEvalBlindReviewDecisionLoader.ResolvedDecision> decisions =
                LongMemEvalBlindReviewDecisionLoader.resolve(privateMap, decisionFile);

        assertThat(decisions).hasSize(2);
        for (int index = 0; index < decisions.size(); index++) {
            LongMemEvalBlindReviewDecisionLoader.ResolvedDecision decision = decisions.get(index);
            JsonNode mapping = privateMap.path("items").get(index);
            assertThat(decision.reviewItemId()).isEqualTo(mapping.path("reviewItemId").asText());
            assertThat(decision.caseId()).isEqualTo(mapping.path("caseId").asText());
            assertThat(decision.candidateId()).isEqualTo(mapping.path("candidateId").asText());
        }
        assertThat(decisions)
                .extracting(LongMemEvalBlindReviewDecisionLoader.ResolvedDecision::decision)
                .containsExactlyInAnyOrder(
                        LongMemEvalBlindReviewDecisionLoader.Decision.CONFIRM,
                        LongMemEvalBlindReviewDecisionLoader.Decision.DISCARD
                );
    }

    @Test
    void rejectsPartialDuplicateUnknownMismatchedAndLeakingDecisionFiles() throws Exception {
        LongMemEvalBlindReviewPackageBuilder.Artifacts artifacts = artifacts();
        JsonNode privateMap = artifacts.privateMapping();

        ObjectNode partial = decisionFile(artifacts, false);
        ((ArrayNode) partial.path("decisions")).remove(1);
        assertRejected(privateMap, partial);

        ObjectNode duplicate = decisionFile(artifacts, false);
        JsonNode firstDecision = duplicate.path("decisions").get(0).deepCopy();
        ((ArrayNode) duplicate.path("decisions")).add(firstDecision);
        assertRejected(privateMap, duplicate);

        ObjectNode unknown = decisionFile(artifacts, false);
        ((ObjectNode) unknown.path("decisions").get(0)).put("reviewItemId", "unknown-item");
        assertRejected(privateMap, unknown);

        ObjectNode invalidDecision = decisionFile(artifacts, false);
        ((ObjectNode) invalidDecision.path("decisions").get(0)).put("decision", "PENDING");
        assertRejected(privateMap, invalidDecision);

        ObjectNode wrongBatch = decisionFile(artifacts, false);
        wrongBatch.put("batchId", UUID.randomUUID().toString());
        assertRejected(privateMap, wrongBatch);

        ObjectNode leaking = decisionFile(artifacts, false);
        leaking.put("question", "target question");
        assertRejected(privateMap, leaking);
    }

    private void assertRejected(JsonNode privateMap, JsonNode decisionFile) {
        assertThatThrownBy(() -> LongMemEvalBlindReviewDecisionLoader.resolve(privateMap, decisionFile))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LongMemEvalBlindReviewPackageBuilder.Artifacts artifacts() {
        return LongMemEvalBlindReviewPackageBuilder.build(
                "longmemeval-30-v1",
                BATCH_ID,
                Set.of("case-1", "case-2"),
                List.of(
                        candidate("case-1", "candidate-1", "prefers tea"),
                        candidate("case-2", "candidate-2", "avoids peanuts")
                )
        );
    }

    private LongMemEvalBlindReviewPackageBuilder.CandidateForReview candidate(
            String caseId,
            String candidateId,
            String content
    ) {
        return new LongMemEvalBlindReviewPackageBuilder.CandidateForReview(
                caseId,
                candidateId,
                "PREFERENCE",
                content,
                "medium",
                "message-" + candidateId,
                "evidence " + content,
                "PENDING"
        );
    }

    private ObjectNode decisionFile(
            LongMemEvalBlindReviewPackageBuilder.Artifacts artifacts,
            boolean reverseOrder
    ) {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("schemaVersion", 1);
        result.put("batchId", BATCH_ID.toString());
        ArrayNode decisions = result.putArray("decisions");
        List<JsonNode> mappings = new java.util.ArrayList<>();
        artifacts.privateMapping().path("items").forEach(mappings::add);
        if (reverseOrder) {
            java.util.Collections.reverse(mappings);
        }
        for (int index = 0; index < mappings.size(); index++) {
            ObjectNode decision = decisions.addObject();
            decision.put("reviewItemId", mappings.get(index).path("reviewItemId").asText());
            decision.put("decision", index == 0 ? "CONFIRM" : "DISCARD");
        }
        return result;
    }
}
