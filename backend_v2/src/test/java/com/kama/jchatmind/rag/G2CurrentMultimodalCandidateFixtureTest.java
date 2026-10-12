package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class G2CurrentMultimodalCandidateFixtureTest {

    private static final String MANIFEST_PATH =
            "rag-eval/datasets/manifests/g2-current-multimodal-v1.json";
    private static final String CASES_PATH = "rag-eval/datasets/cases/g2-current-multimodal-v1.jsonl";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void candidateRuntimeUuidsAreStableAcrossLoads() throws Exception {
        G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile firstLoad =
                G2CurrentMultimodalCandidateFixtureLoader.load(MANIFEST_PATH);
        G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile secondLoad =
                G2CurrentMultimodalCandidateFixtureLoader.load(MANIFEST_PATH);

        assertThat(secondLoad.assets()).containsExactlyElementsOf(firstLoad.assets());
        assertThat(secondLoad.assetImportPlan()).isEqualTo(firstLoad.assetImportPlan());
        List<String> assetIds = runtimeIdentifiers(firstLoad.assets(), "runtimeAssetUuid");
        assertThat(assetIds).hasSize(2).doesNotHaveDuplicates()
                .allMatch(id -> id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset left : firstLoad.assets()) {
            for (G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset right : firstLoad.assets()) {
                assertThat(left.runtimeDocumentUuid().equals(right.runtimeDocumentUuid()))
                        .isEqualTo(left.sourceDocumentId().equals(right.sourceDocumentId()));
                assertThat(left.runtimeChunkUuid().equals(right.runtimeChunkUuid()))
                        .isEqualTo(left.logicalChunkId().equals(right.logicalChunkId()));
            }
        }
    }

    @Test
    void candidateProfileExposesDeterministicAssetImportPlan() throws Exception {
        G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile =
                G2CurrentMultimodalCandidateFixtureLoader.load(MANIFEST_PATH);

        JsonNode importPlan = OBJECT_MAPPER.valueToTree(profile).path("assetImportPlan");

        assertThat(importPlan.isObject()).isTrue();
        assertThat(importPlan.path("knowledgeBaseId").asText())
                .matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
        assertThat(importPlan.path("documentRows").size()).isEqualTo(2);
        assertThat(importPlan.path("chunkRows").size()).isEqualTo(2);
        assertThat(importPlan.path("assetRows").size()).isEqualTo(2);
        assertThat(importPlan.path("assetChunkRelations").size()).isEqualTo(2);
        assertThat(importPlan.path("chunkAssetMetadataByLogicalId").size()).isEqualTo(2);
        JsonNode documentRows = importPlan.path("documentRows");
        JsonNode chunkRows = importPlan.path("chunkRows");
        JsonNode assetRows = importPlan.path("assetRows");
        JsonNode relations = importPlan.path("assetChunkRelations");
        JsonNode metadataByLogicalId = importPlan.path("chunkAssetMetadataByLogicalId");
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset asset : profile.assets()) {
            JsonNode document = findByText(documentRows, "documentId", asset.runtimeDocumentUuid());
            assertThat(document.path("knowledgeBaseId").asText())
                    .isEqualTo(importPlan.path("knowledgeBaseId").asText());
            assertThat(document.path("filename").asText()).isEqualTo(asset.chunkPayload().sourceName());
            assertThat(document.path("filetype").asText()).isEqualTo(asset.chunkPayload().sourceType());
            assertThat(document.path("sizeBytes").asLong()).isEqualTo(asset.chunkPayload().documentSizeBytes());
            assertThat(document.path("metadata").path("sourceDocumentId").asText())
                    .isEqualTo(asset.sourceDocumentId());

            JsonNode chunk = findByText(chunkRows, "logicalChunkId", asset.logicalChunkId());
            assertThat(chunk.path("chunkId").asText()).isEqualTo(asset.runtimeChunkUuid());
            assertThat(chunk.path("knowledgeBaseId").asText()).isEqualTo(importPlan.path("knowledgeBaseId").asText());
            assertThat(chunk.path("documentId").asText()).isEqualTo(asset.runtimeDocumentUuid());
            assertThat(chunk.path("content").asText()).isEqualTo(asset.chunkPayload().content());
            assertThat(chunk.path("embeddingText").asText()).isEqualTo(asset.chunkPayload().embeddingText());
            assertThat(chunk.path("titleSearchText").asText()).isEqualTo(asset.chunkPayload().titleSearchText());

            JsonNode row = findByText(assetRows, "assetId", asset.runtimeAssetUuid());
            assertThat(row.path("documentId").asText()).isEqualTo(asset.runtimeDocumentUuid());
            assertThat(row.path("assetType").asText()).isEqualTo(asset.assetType());
            assertThat(row.path("assetKey").asText()).isEqualTo(asset.assetKey());
            assertThat(row.path("contentHash").asText()).isEqualTo(asset.contentHash());
            assertThat(row.path("status").asText()).isEqualTo("READY");
            assertThat(OBJECT_MAPPER.readTree(row.path("locatorJson").asText()))
                    .isEqualTo(OBJECT_MAPPER.valueToTree(asset.locator()));

            JsonNode relation = findByText(relations, "assetId", asset.runtimeAssetUuid());
            assertThat(relation.path("chunkId").asText()).isEqualTo(asset.runtimeChunkUuid());
            assertThat(relation.path("assetDocumentId").asText()).isEqualTo(asset.runtimeDocumentUuid());
            assertThat(relation.path("chunkDocumentId").asText()).isEqualTo(asset.runtimeDocumentUuid());
            JsonNode chunkAsset = metadataByLogicalId.path(asset.logicalChunkId()).path("asset");
            assertThat(chunkAsset.path("id").asText()).isEqualTo(asset.runtimeAssetUuid());
            assertThat(chunkAsset.path("type").asText()).isEqualTo(asset.assetType());
        }
    }

    @Test
    void candidateAssetsCarryProductionChunkPayloadForRuntimeImport() throws Exception {
        G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile =
                G2CurrentMultimodalCandidateFixtureLoader.load(MANIFEST_PATH);
        JsonNode assets = OBJECT_MAPPER.valueToTree(profile.assets());

        for (JsonNode asset : assets) {
            JsonNode chunk = asset.path("chunkPayload");
            assertThat(chunk.isObject()).isTrue();
            assertThat(chunk.path("sourceType").asText()).isIn("md", "pdf");
            assertThat(chunk.path("sourceName").asText()).isNotBlank();
            assertThat(chunk.path("contentPath").asText()).isNotBlank();
            assertThat(chunk.path("documentSizeBytes").asLong()).isPositive();
            assertThat(chunk.path("embeddingText").asText()).isNotBlank();
            assertThat(chunk.path("titleSearchText").asText()).isNotBlank();
            assertThat(chunk.path("metadata").path("asset").path("id").asText())
                    .isEqualTo(asset.path("runtimeAssetUuid").asText());
            assertThat(chunk.path("metadata").path("asset").path("type").asText())
                    .isEqualTo(asset.path("assetType").asText());
        }
    }

    @Test
    void candidateCasesFreezeTheExpectedMultimodalRoute() throws Exception {
        byte[] caseBytes;
        try (var inputStream = new ClassPathResource(CASES_PATH).getInputStream()) {
            caseBytes = inputStream.readAllBytes();
        }
        List<JsonNode> cases = new java.util.ArrayList<>();
        for (String line : new String(caseBytes, StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isBlank()) {
                cases.add(OBJECT_MAPPER.readTree(line));
            }
        }

        assertThat(cases).hasSize(4);
        for (JsonNode candidateCase : cases) {
            assertThat(candidateCase.path("expectedRoute").asText())
                    .as("expected route for %s", candidateCase.path("caseId").asText())
                    .isEqualTo("MULTIMODAL_RAG");
        }
    }

    @Test
    void loadsHashVerifiedTableAndPdfGoldWithoutAdmittingCandidateToRelease() throws Exception {
        G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile =
                G2CurrentMultimodalCandidateFixtureLoader.load(MANIFEST_PATH);

        assertThat(profile.datasetId()).isEqualTo("g2-current-multimodal-v1");
        assertThat(profile.status()).isEqualTo("candidate");
        assertThat(profile.releaseEligible()).isFalse();
        assertThat(profile.manifestSha256()).hasSize(64);
        assertThat(profile.caseSha256()).hasSize(64);
        assertThat(profile.corpusSha256()).hasSize(64);
        assertThat(profile.cases()).hasSize(4)
                .allMatch(candidateCase -> "pending".equals(candidateCase.reviewStatus()));
        assertThat(profile.cases().stream().filter(G2CurrentMultimodalCandidateFixtureLoader.CandidateCase::shouldAbstain))
                .hasSize(1);
        assertThat(profile.assets()).hasSize(2);

        assertThat(profile.cases())
                .allMatch(candidateCase -> "MULTIMODAL_RAG".equals(candidateCase.expectedRoute()));

        G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset table = asset(profile, "TABLE");
        assertThat(table.assetKey()).isEqualTo("table-1");
        assertThat(table.locator()).isEqualTo(Map.of("startLine", 7, "endLine", 10));
        assertThat(table.content()).contains("2-3 个工作日", "北京、上海、广州、深圳");

        G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset pdf = asset(profile, "PDF_PAGE_TEXT");
        assertThat(pdf.assetKey()).isEqualTo("page-1");
        assertThat(pdf.locator()).isEqualTo(Map.of("pageNumber", 1));
        assertThat(pdf.content()).contains("Express delivery is next business day.");

        G2CurrentMultimodalCandidateFixtureLoader.CandidateCase pdfCase = profile.cases().stream()
                .filter(candidateCase -> candidateCase.goldAsset() != null
                        && "PDF_PAGE_TEXT".equals(candidateCase.goldAsset().assetType()))
                .findFirst()
                .orElseThrow();
        assertThat(pdfCase.goldChunkLogicalId()).isEqualTo(pdf.logicalChunkId());

        assertThatThrownBy(() -> RagEvaluationDatasetLoader.load(MANIFEST_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema v1");
    }

    private G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset asset(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            String assetType
    ) {
        List<G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset> matches = profile.assets().stream()
                .filter(asset -> assetType.equals(asset.assetType()))
                .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private List<String> runtimeIdentifiers(
            List<G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset> assets,
            String property
    ) {
        JsonNode json = OBJECT_MAPPER.valueToTree(assets);
        List<String> ids = new java.util.ArrayList<>();
        json.forEach(asset -> ids.add(asset.path(property).asText()));
        return List.copyOf(ids);
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
