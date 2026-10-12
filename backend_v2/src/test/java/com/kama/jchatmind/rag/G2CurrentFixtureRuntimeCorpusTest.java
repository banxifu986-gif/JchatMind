package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class G2CurrentFixtureRuntimeCorpusTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsEveryFrozenGoldChunkToItsParsedSourceDocument() throws Exception {
        G2CurrentFixtureRuntimeCorpus fixture = G2CurrentFixtureRuntimeCorpusLoader.load(
                "rag-eval/datasets/manifests/fixture-fast-v1.json"
        );

        assertThat(fixture.datasetId()).isEqualTo("fixture-fast-v1");
        assertThat(fixture.cases()).hasSize(20);
        assertThat(fixture.cases()).filteredOn(RagEvaluationCase::shouldAbstain).hasSize(2);
        assertThat(fixture.caseSha256()).matches("[0-9a-f]{64}");
        assertThat(fixture.corpusSha256()).matches("[0-9a-f]{64}");

        List<RagEvaluationCase> answerableCases = fixture.cases().stream()
                .filter(item -> !item.shouldAbstain())
                .toList();
        assertThat(answerableCases).hasSize(18).allSatisfy(item -> {
            assertThat(item.goldChunkIds()).isNotEmpty().allSatisfy(logicalChunkId -> {
                G2CurrentFixtureRuntimeCorpus.Chunk chunk = fixture.chunksByLogicalId().get(logicalChunkId);
                assertThat(chunk).isNotNull();
                assertThat(item.sourceDocumentIds()).contains(chunk.sourceDocumentId());
            });
        });
    }

    @Test
    void buildsStableRuntimeIdsAndProductionEmbeddingInput() throws Exception {
        G2CurrentFixtureRuntimeCorpus firstRun = G2CurrentFixtureRuntimeCorpusLoader.load(
                "rag-eval/datasets/manifests/fixture-fast-v1.json"
        );
        G2CurrentFixtureRuntimeCorpus secondRun = G2CurrentFixtureRuntimeCorpusLoader.load(
                "rag-eval/datasets/manifests/fixture-fast-v1.json"
        );

        String logicalChunkId = "orders-refund#订单退款#0";
        JsonNode firstFixture = objectMapper.valueToTree(firstRun);
        JsonNode firstChunk = objectMapper.valueToTree(firstRun.chunksByLogicalId().get(logicalChunkId));
        JsonNode secondChunk = objectMapper.valueToTree(secondRun.chunksByLogicalId().get(logicalChunkId));
        JsonNode metadata = firstChunk.path("metadata");
        String fixtureSha256 = firstFixture.path("fixtureSha256").asText();

        assertThat(fixtureSha256).matches("[0-9a-f]{64}");
        assertThat(firstChunk.path("runtimeDocumentUuid").asText()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        );
        assertThat(firstChunk.path("documentSizeBytes").asLong()).isEqualTo(
                new ClassPathResource("rag-eval/fixtures/fixture-kb.md").contentLength()
        );
        assertThat(firstChunk.path("runtimeChunkUuid").asText()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        );
        assertThat(firstChunk.path("runtimeDocumentUuid").asText()).isEqualTo(UUID.nameUUIDFromBytes(
                ("fixture-fast-v1:" + fixtureSha256 + ":document:orders-refund")
                        .getBytes(StandardCharsets.UTF_8)
        ).toString());
        assertThat(firstChunk.path("runtimeChunkUuid").asText()).isEqualTo(UUID.nameUUIDFromBytes(
                ("fixture-fast-v1:" + fixtureSha256 + ":chunk:" + logicalChunkId)
                        .getBytes(StandardCharsets.UTF_8)
        ).toString());
        assertThat(firstChunk.path("embeddingText").asText()).isEqualTo(
                "订单退款\n订单退款\n用户申请退款后，系统需要先校验订单状态。只有已支付且未发货的订单允许直接原路退款。退款申请提交后，财务系统会生成退款单并记录处理状态。"
        );
        assertThat(firstChunk.path("titleSearchText").asText()).isNotBlank();
        assertThat(metadata.path("sourceName").asText()).isEqualTo("fixture-kb.md");
        assertThat(metadata.path("sourceType").asText()).isEqualTo("md");
        assertThat(metadata.path("title").asText()).isEqualTo("订单退款");
        assertThat(metadata.path("contentPath").asText()).isEqualTo("订单退款");
        assertThat(metadata.path("sectionIndex").asInt()).isZero();
        assertThat(firstChunk.path("runtimeDocumentUuid").asText())
                .isEqualTo(secondChunk.path("runtimeDocumentUuid").asText());
        assertThat(firstChunk.path("runtimeChunkUuid").asText())
                .isEqualTo(secondChunk.path("runtimeChunkUuid").asText());
    }

    @Test
    void assemblesDeterministicCandidatesAndRejectsMisalignedVectors() throws Exception {
        G2CurrentFixtureRuntimeCorpus fixture = G2CurrentFixtureRuntimeCorpusLoader.load(
                "rag-eval/datasets/manifests/fixture-fast-v1.json"
        );
        int chunkCount = fixture.chunksByLogicalId().size();
        List<float[]> embeddings = IntStream.range(0, chunkCount)
                .mapToObj(index -> new float[1024])
                .toList();
        List<VchordBm25ProjectionService.Projection> projections = IntStream.range(0, chunkCount)
                .mapToObj(index -> new VchordBm25ProjectionService.Projection("{1:1}", "{2:1}", 1))
                .toList();

        List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates =
                G2CurrentFixtureRuntimeCandidateBuilder.build(fixture, embeddings, projections);

        assertThat(candidates).hasSize(chunkCount);
        assertThat(candidates)
                .extracting(item -> item.chunk().logicalChunkId())
                .isSorted();
        assertThat(candidates).allSatisfy(item -> {
            assertThat(item.embedding()).hasSize(1024);
            assertThat(item.projection().indexVersion()).isEqualTo(1);
        });
        assertThatThrownBy(() -> G2CurrentFixtureRuntimeCandidateBuilder.build(
                fixture,
                embeddings.subList(1, embeddings.size()),
                projections
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsStableImportIdentityForTheCompleteRuntimeFixture() throws Exception {
        G2CurrentFixtureRuntimeCorpus fixture = G2CurrentFixtureRuntimeCorpusLoader.load(
                "rag-eval/datasets/manifests/fixture-fast-v1.json"
        );
        int chunkCount = fixture.chunksByLogicalId().size();
        List<float[]> embeddings = IntStream.range(0, chunkCount)
                .mapToObj(index -> new float[1024])
                .toList();
        List<VchordBm25ProjectionService.Projection> projections = IntStream.range(0, chunkCount)
                .mapToObj(index -> new VchordBm25ProjectionService.Projection("{1:1}", "{2:1}", 1))
                .toList();
        List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates =
                G2CurrentFixtureRuntimeCandidateBuilder.build(fixture, embeddings, projections);

        G2CurrentFixtureRuntimeImportPlan.Prepared firstPlan =
                G2CurrentFixtureRuntimeImportPlan.prepare(fixture, candidates);
        G2CurrentFixtureRuntimeImportPlan.Prepared secondPlan =
                G2CurrentFixtureRuntimeImportPlan.prepare(fixture, candidates);

        assertThat(firstPlan.datasetId()).isEqualTo("fixture-fast-v1");
        assertThat(firstPlan.fixtureSha256()).matches("[0-9a-f]{64}");
        assertThat(firstPlan.knowledgeBaseId()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        );
        assertThat(firstPlan.logicalChunkIdByRuntimeUuid()).hasSize(chunkCount);
        assertThat(firstPlan.logicalChunkIdByRuntimeUuid().values())
                .containsExactlyInAnyOrderElementsOf(fixture.chunksByLogicalId().keySet());
        assertThat(firstPlan.knowledgeBaseId()).isEqualTo(secondPlan.knowledgeBaseId());
        assertThat(firstPlan.fixtureSha256()).isEqualTo(secondPlan.fixtureSha256());
        assertThatThrownBy(() -> G2CurrentFixtureRuntimeImportPlan.prepare(
                fixture,
                candidates.subList(1, candidates.size())
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preparesDatabaseMetadataWithoutDroppingProductionChunkFields() throws Exception {
        G2CurrentFixtureRuntimeCorpus fixture = G2CurrentFixtureRuntimeCorpusLoader.load(
                "rag-eval/datasets/manifests/fixture-fast-v1.json"
        );
        int chunkCount = fixture.chunksByLogicalId().size();
        List<float[]> embeddings = IntStream.range(0, chunkCount)
                .mapToObj(index -> new float[1024])
                .toList();
        List<VchordBm25ProjectionService.Projection> projections = IntStream.range(0, chunkCount)
                .mapToObj(index -> new VchordBm25ProjectionService.Projection("{1:1}", "{2:1}", 1))
                .toList();
        List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates =
                G2CurrentFixtureRuntimeCandidateBuilder.build(fixture, embeddings, projections);
        G2CurrentFixtureRuntimeImportPlan.Prepared plan =
                G2CurrentFixtureRuntimeImportPlan.prepare(fixture, candidates);
        JsonNode serializedPlan = objectMapper.valueToTree(plan);

        JsonNode knowledgeBaseMetadata = serializedPlan.path("knowledgeBaseMetadata");
        assertThat(knowledgeBaseMetadata.path("evaluationNamespace").asText()).isEqualTo("rag-eval");
        assertThat(knowledgeBaseMetadata.path("datasetId").asText()).isEqualTo("fixture-fast-v1");
        assertThat(knowledgeBaseMetadata.path("fixtureSha256").asText()).isEqualTo(plan.fixtureSha256());

        JsonNode chunkMetadata = serializedPlan
                .path("chunkMetadataByLogicalChunkId")
                .path("orders-refund#订单退款#0");
        assertThat(chunkMetadata.path("logicalChunkId").asText()).isEqualTo("orders-refund#订单退款#0");
        assertThat(chunkMetadata.path("evaluationNamespace").asText()).isEqualTo("rag-eval");
        assertThat(chunkMetadata.path("datasetId").asText()).isEqualTo("fixture-fast-v1");
        assertThat(chunkMetadata.path("sourceName").asText()).isEqualTo("fixture-kb.md");
        assertThat(chunkMetadata.path("sourceDocumentId").asText()).isEqualTo("orders-refund");
        assertThat(chunkMetadata.path("sourceType").asText()).isEqualTo("md");
        assertThat(chunkMetadata.path("title").asText()).isEqualTo("订单退款");
        assertThat(chunkMetadata.path("contentPath").asText()).isEqualTo("订单退款");
        assertThat(chunkMetadata.path("sectionIndex").asInt()).isZero();
    }

    @Test
    void acceptsOnlyTheIsolatedRuntimeDatabaseInsideAnExplicitTransaction() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn("jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval");
        when(connection.getCatalog()).thenReturn("jchatmind_rag_eval");
        when(connection.getAutoCommit()).thenReturn(false);

        assertThatCode(() -> G2CurrentFixtureRuntimeDatabaseImporter.requireIsolatedTransaction(connection))
                .doesNotThrowAnyException();

        when(metadata.getURL()).thenReturn("jdbc:postgresql://127.0.0.1:5432/postgres");
        assertThatThrownBy(() -> G2CurrentFixtureRuntimeDatabaseImporter.requireIsolatedTransaction(connection))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("127.0.0.1:55432/jchatmind_rag_eval");

        when(metadata.getURL()).thenReturn("jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval");
        when(connection.getAutoCommit()).thenReturn(true);
        assertThatThrownBy(() -> G2CurrentFixtureRuntimeDatabaseImporter.requireIsolatedTransaction(connection))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("autocommit");
    }

    @Test
    void validatesEveryRuntimeCandidateAgainstTheImportPlanBeforeDatabaseWrites() throws Exception {
        G2CurrentFixtureRuntimeCorpus fixture = G2CurrentFixtureRuntimeCorpusLoader.load(
                "rag-eval/datasets/manifests/fixture-fast-v1.json"
        );
        int chunkCount = fixture.chunksByLogicalId().size();
        List<float[]> embeddings = IntStream.range(0, chunkCount)
                .mapToObj(index -> new float[1024])
                .toList();
        List<VchordBm25ProjectionService.Projection> projections = IntStream.range(0, chunkCount)
                .mapToObj(index -> new VchordBm25ProjectionService.Projection("{1:1}", "{2:1}", 1))
                .toList();
        List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates =
                G2CurrentFixtureRuntimeCandidateBuilder.build(fixture, embeddings, projections);
        G2CurrentFixtureRuntimeImportPlan.Prepared plan =
                G2CurrentFixtureRuntimeImportPlan.prepare(fixture, candidates);
        G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate emptyParent = candidates.stream()
                .filter(item -> "logistics#发货与物流#0".equals(item.chunk().logicalChunkId()))
                .findFirst()
                .orElseThrow();

        assertThat(emptyParent.chunk().content()).isEmpty();
        assertThat(emptyParent.chunk().metadata().get("sectionType")).isEqualTo("PARENT_OVERVIEW");

        assertThatCode(() -> G2CurrentFixtureRuntimeDatabaseImporter.validateCandidates(plan, candidates))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> G2CurrentFixtureRuntimeDatabaseImporter.validateCandidates(
                plan,
                candidates.subList(1, candidates.size())
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void importEntryRejectsTheWrongDatabaseBeforePreparingAnySql() throws Exception {
        G2CurrentFixtureRuntimeCorpus fixture = G2CurrentFixtureRuntimeCorpusLoader.load(
                "rag-eval/datasets/manifests/fixture-fast-v1.json"
        );
        int chunkCount = fixture.chunksByLogicalId().size();
        List<float[]> embeddings = IntStream.range(0, chunkCount)
                .mapToObj(index -> new float[1024])
                .toList();
        List<VchordBm25ProjectionService.Projection> projections = IntStream.range(0, chunkCount)
                .mapToObj(index -> new VchordBm25ProjectionService.Projection("{1:1}", "{2:1}", 1))
                .toList();
        List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates =
                G2CurrentFixtureRuntimeCandidateBuilder.build(fixture, embeddings, projections);
        G2CurrentFixtureRuntimeImportPlan.Prepared plan =
                G2CurrentFixtureRuntimeImportPlan.prepare(fixture, candidates);
        Connection connection = mock(Connection.class);
        DatabaseMetaData databaseMetaData = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getURL()).thenReturn("jdbc:postgresql://127.0.0.1:5432/postgres");
        when(connection.getCatalog()).thenReturn("postgres");
        when(connection.getAutoCommit()).thenReturn(false);

        assertThatThrownBy(() -> G2CurrentFixtureRuntimeDatabaseImporter.importFixture(
                connection, "900000000004", plan, candidates
        )).isInstanceOf(IllegalStateException.class);
        verify(connection, never()).prepareStatement(anyString());
    }
}
