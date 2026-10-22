package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class G2CurrentFixtureCitationCandidateLoaderTest {

    private static final String MANIFEST_PATH =
            "rag-eval/datasets/manifests/fixture-fast-v2-citation-candidate.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void loadsApprovedAlternativeGoldCitationGroupsWithoutMakingCandidateReleaseEligible() throws IOException {
        G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate =
                G2CurrentFixtureCitationCandidateLoader.load(MANIFEST_PATH);

        assertThat(candidate.datasetId()).isEqualTo("fixture-fast-v2-citation-candidate");
        assertThat(candidate.parentDatasetId()).isEqualTo("fixture-fast-v1");
        assertThat(candidate.status()).isEqualTo("candidate");
        assertThat(candidate.reviewStatus()).isEqualTo("approved");
        assertThat(candidate.releaseEligible()).isFalse();
        assertThat(candidate.caseSha256()).hasSize(64);
        assertThat(candidate.corpusSha256()).hasSize(64);
        assertThat(candidate.cases()).hasSize(20)
                .allMatch(item -> "approved".equals(item.reviewStatus()));

        assertThat(candidate.caseById("fixture-fast-v2-citation-candidate-002").goldCitationGroups())
                .containsExactly(List.of(
                        "orders-refund#发货时效#0",
                        "logistics#发货与物流>发货时效>普通商品#0"
                ));
        assertThat(candidate.caseById("fixture-fast-v2-citation-candidate-003").goldCitationGroups())
                .containsExactly(List.of(
                        "orders-refund#会员积分#0",
                        "membership#会员体系与积分规则>积分退回#0"
                ));
        assertThat(candidate.caseById("fixture-fast-v2-citation-candidate-012").goldCitationGroups())
                .containsExactly(
                        List.of("logistics#发货与物流>订单退款#0"),
                        List.of("returns-policy#退货退款政策>退款流程>退款到账#0")
                );
        assertThat(candidate.caseById("fixture-fast-v2-citation-candidate-019").goldCitationGroups()).isEmpty();

        assertThatThrownBy(() -> RagEvaluationDatasetLoader.load(MANIFEST_PATH))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void loadsPendingReviewSnapshotWithoutMakingCandidateReleaseEligible() throws Exception {
        byte[] manifestBytes = readResource(MANIFEST_PATH);
        byte[] approvedCaseBytes = readResource(
                "rag-eval/datasets/cases/fixture-fast-v2-citation-candidate.jsonl"
        );
        byte[] pendingCaseBytes = withReviewStatus(approvedCaseBytes, "pending");
        ObjectNode pendingManifest = (ObjectNode) OBJECT_MAPPER.readTree(manifestBytes);
        pendingManifest.put("reviewStatus", "pending");
        pendingManifest.put("caseSha256", sha256(pendingCaseBytes));

        G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate =
                G2CurrentFixtureCitationCandidateLoader.load(
                        MANIFEST_PATH, OBJECT_MAPPER.writeValueAsBytes(pendingManifest), pendingCaseBytes);

        assertThat(candidate.reviewStatus()).isEqualTo("pending");
        assertThat(candidate.cases()).hasSize(20)
                .allMatch(item -> "pending".equals(item.reviewStatus()));
        assertThat(candidate.status()).isEqualTo("candidate");
        assertThat(candidate.releaseEligible()).isFalse();
    }

    @Test
    void loadsApprovedReviewSnapshotWithoutPromotingCandidate() throws Exception {
        byte[] manifestBytes = readResource(MANIFEST_PATH);
        byte[] caseBytes = readResource(
                "rag-eval/datasets/cases/fixture-fast-v2-citation-candidate.jsonl"
        );
        byte[] reviewedCaseBytes = withReviewStatus(caseBytes, "approved");
        ObjectNode reviewedManifest = (ObjectNode) OBJECT_MAPPER.readTree(manifestBytes);
        reviewedManifest.put("reviewStatus", "approved");
        reviewedManifest.put("caseSha256", sha256(reviewedCaseBytes));

        G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate =
                G2CurrentFixtureCitationCandidateLoader.load(
                        MANIFEST_PATH,
                        OBJECT_MAPPER.writeValueAsBytes(reviewedManifest),
                        reviewedCaseBytes
                );

        assertThat(candidate.reviewStatus()).isEqualTo("approved");
        assertThat(candidate.cases()).hasSize(20)
                .allMatch(item -> "approved".equals(item.reviewStatus()));
        assertThat(candidate.status()).isEqualTo("candidate");
        assertThat(candidate.releaseEligible()).isFalse();
        assertThatThrownBy(() -> RagEvaluationDatasetLoader.load(MANIFEST_PATH))
                .isInstanceOf(JsonProcessingException.class);
    }

    private byte[] readResource(String path) throws IOException {
        try (InputStream inputStream = new ClassPathResource(path).getInputStream()) {
            return inputStream.readAllBytes();
        }
    }

    private byte[] withReviewStatus(byte[] caseBytes, String reviewStatus) {
        return Arrays.stream(new String(caseBytes, StandardCharsets.UTF_8).split("\\R"))
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        ObjectNode candidateCase = (ObjectNode) OBJECT_MAPPER.readTree(line);
                        candidateCase.put("reviewStatus", reviewStatus);
                        return OBJECT_MAPPER.writeValueAsString(candidateCase);
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow()
                .concat("\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
