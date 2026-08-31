package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class RagBadCaseManifestTest {

    private static final Path MANIFEST = Path.of(
            "src", "test", "resources", "rag-eval", "badcase", "rag-badcase-v1.json"
    );
    private static final Path SOURCE_MANIFEST = Path.of(
            "src", "test", "resources", "rag-eval", "datasets", "manifests", "g2-pre-bm25-v1.json"
    );

    @Test
    void shouldFreezeReviewedRefusalViolationsWithTraceableEvidence() throws Exception {
        assertThat(Files.exists(MANIFEST)).isTrue();

        JsonNode root;
        try (InputStream input = Files.newInputStream(MANIFEST)) {
            root = new ObjectMapper().readTree(input);
        }

        assertThat(root.path("datasetId").asText()).isEqualTo("rag-badcase-v1");
        assertThat(root.path("schemaVersion").asText()).isEqualTo("1");
        assertThat(root.path("status").asText()).isEqualTo("frozen");
        assertThat(root.path("sourceDataset").asText()).isEqualTo("g2-pre-bm25-v1");
        assertThat(root.path("sourceManifestSha256").asText()).isEqualTo(sha256(SOURCE_MANIFEST));

        JsonNode cases = root.path("cases");
        assertThat(cases.isArray()).isTrue();
        assertThat(cases).hasSize(4);

        Set<String> caseIds = new HashSet<>();
        Map<String, JsonNode> casesById = new LinkedHashMap<>();
        for (JsonNode badCase : cases) {
            String caseId = badCase.path("caseId").asText();
            assertThat(caseIds.add(caseId)).isTrue();
            casesById.put(caseId, badCase);
            assertThat(badCase.path("reviewStatus").asText()).isEqualTo("reviewed");
            assertThat(badCase.path("reviewedBy").asText()).isNotBlank();
            assertThat(badCase.path("query").asText()).isNotBlank();
            assertThat(badCase.path("queryType").asText()).isNotBlank();
            assertThat(badCase.path("kbScope").isArray()).isTrue();
            assertThat(badCase.path("failureStage").asText()).isNotBlank();
            assertThat(badCase.path("failureType").asText()).isNotBlank();
            assertThat(badCase.path("severity").asText()).isIn("P0", "P1", "P2");
            assertThat(badCase.path("discoveredVersion").asText()).isEqualTo("g2-pre-bm25-v1");
            assertThat(badCase.path("sourceSha256").asText()).matches("[0-9a-f]{64}");
        }

        assertThat(caseIds).containsExactlyInAnyOrder(
                "g2-pre-bm25-v1-007-abstention",
                "g2-pre-bm25-v1-008-abstention",
                "g2-pre-bm25-v1-002-rank-regression",
                "g2-pre-bm25-v1-009-citation-rank-regression"
        );
        assertAbstentionCase(casesById.get("g2-pre-bm25-v1-007-abstention"));
        assertAbstentionCase(casesById.get("g2-pre-bm25-v1-008-abstention"));

        JsonNode rankRegression = casesById.get("g2-pre-bm25-v1-002-rank-regression");
        assertThat(rankRegression.path("expectedRoute").asText()).isEqualTo("PRIVATE_RAG");
        assertThat(rankRegression.path("goldChunkIds").toString())
                .contains("g2-architecture#JVM 词法候选边界#0");
        assertThat(rankRegression.path("failureType").asText()).isEqualTo("retrieval_rank_regression");
        assertThat(rankRegression.path("evidence").path("branchFusionContract").asText())
                .isEqualTo("outer_rrf_one_vote_per_branch");
        assertThat(rankRegression.path("evidence").path("disposition").asText())
                .isEqualTo("keep_r0_default_not_fixed");
        assertThat(rankRegression.path("fixedVersion").isNull()).isTrue();
        assertThat(rankRegression.path("regressionStatus").asText()).isEqualTo("development-regression");

        JsonNode citationRegression = casesById.get("g2-pre-bm25-v1-009-citation-rank-regression");
        assertThat(citationRegression.path("expectedRoute").asText()).isEqualTo("PRIVATE_RAG");
        assertThat(citationRegression.path("failureType").asText()).isEqualTo("citation_rank_regression");
        assertThat(citationRegression.path("expectedCitation").path("chunkId").asText())
                .isEqualTo("architecture.pdf#第 2 页#0");
        assertThat(citationRegression.path("expectedCitation").path("pageNumber").asInt()).isEqualTo(2);
        assertThat(citationRegression.path("evidence").path("branchFusionContract").asText())
                .isEqualTo("outer_rrf_one_vote_per_branch");
        assertThat(citationRegression.path("evidence").path("disposition").asText())
                .isEqualTo("keep_r0_default_not_fixed");
        assertThat(citationRegression.path("fixedVersion").isNull()).isTrue();
        assertThat(citationRegression.path("regressionStatus").asText()).isEqualTo("development-regression");
    }

    private void assertAbstentionCase(JsonNode badCase) {
        assertThat(badCase.path("expectedRoute").asText()).isEqualTo("ABSTAIN");
        assertThat(badCase.path("goldFacts").isEmpty()).isTrue();
        assertThat(badCase.path("goldChunkIds").isEmpty()).isTrue();
        assertThat(badCase.path("shouldAbstain").asBoolean()).isTrue();
        assertThat(badCase.path("expectedCitation").isNull()).isTrue();
        assertThat(badCase.path("failureType").asText()).isEqualTo("abstention_violation");
        assertThat(badCase.path("fixedVersion").asText()).isNotBlank();
        assertThat(badCase.path("regressionStatus").asText()).isEqualTo("fixed");
        assertThat(badCase.path("evidence").path("variants").toString())
                .contains("R0", "R1", "R2");
        assertThat(badCase.path("evidence").path("abstentionViolations").asInt()).isEqualTo(2);
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        );
    }
}
