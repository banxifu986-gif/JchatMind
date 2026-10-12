package com.kama.jchatmind.rag;

import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class G2CurrentMultimodalCandidateDatabaseImporterTest {

    private static final String MANIFEST_PATH =
            "rag-eval/datasets/manifests/g2-current-multimodal-v1.json";
    private static final String OWNER_ID = "900000000004";

    @Test
    void acceptsCandidateOnlyRowsWithCompleteAssetChunkLinks() throws Exception {
        G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan = runtimePlan();

        assertThatCode(() -> G2CurrentMultimodalCandidateDatabaseImporter.validateImport(
                OWNER_ID, runtimePlan
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsBrokenAssetChunkRelationAndNonNumericOwner() throws Exception {
        G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan = runtimePlan();
        G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan = runtimePlan.assetImportPlan();
        G2CurrentMultimodalCandidateFixtureLoader.AssetChunkRelation relation = plan.assetChunkRelations().get(0);
        G2CurrentMultimodalCandidateFixtureLoader.AssetChunkRelation brokenRelation =
                new G2CurrentMultimodalCandidateFixtureLoader.AssetChunkRelation(
                        relation.assetId(),
                        "00000000-0000-0000-0000-000000000000",
                        relation.assetDocumentId(),
                        relation.chunkDocumentId()
                );
        G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan brokenPlan =
                new G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan(
                        plan.knowledgeBaseId(),
                        plan.knowledgeBaseMetadata(),
                        plan.candidateOnly(),
                        plan.documentRows(),
                        plan.chunkRows(),
                        plan.assetRows(),
                        List.of(brokenRelation, plan.assetChunkRelations().get(1)),
                        plan.chunkAssetMetadataByLogicalId()
                );
        G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan brokenRuntimePlan =
                new G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan(
                        brokenPlan, true, runtimePlan.chunks()
                );

        assertThatThrownBy(() -> G2CurrentMultimodalCandidateDatabaseImporter.validateImport(
                OWNER_ID, brokenRuntimePlan
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> G2CurrentMultimodalCandidateDatabaseImporter.validateImport(
                "candidate-owner", runtimePlan
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesWrongDatabaseBeforePreparingAnySql() throws Exception {
        AtomicBoolean preparedStatementRequested = new AtomicBoolean();
        Connection connection = connectionFor(
                "jdbc:postgresql://127.0.0.1:55432/application_db", preparedStatementRequested
        );

        assertThatThrownBy(() -> G2CurrentMultimodalCandidateDatabaseImporter.importCandidate(
                connection, OWNER_ID, runtimePlan()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jchatmind_rag_eval");
        assertThat(preparedStatementRequested.get()).isFalse();
    }

    private G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan() throws Exception {
        G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile =
                G2CurrentMultimodalCandidateFixtureLoader.load(MANIFEST_PATH);
        List<float[]> embeddings = List.of(embedding(1), embedding(2));
        List<VchordBm25ProjectionService.Projection> projections = List.of(
                projection("{1:1}", "{2:1}"), projection("{3:1}", "{4:1}")
        );
        return G2CurrentMultimodalCandidateRuntimeBuilder.build(profile, embeddings, projections);
    }

    private Connection connectionFor(String url, AtomicBoolean preparedStatementRequested) {
        DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> "getURL".equals(method.getName()) ? url : null
        );
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metadata;
                    case "getCatalog" -> "jchatmind_rag_eval";
                    case "getAutoCommit" -> false;
                    case "prepareStatement" -> {
                        preparedStatementRequested.set(true);
                        yield null;
                    }
                    default -> null;
                }
        );
    }

    private float[] embedding(float firstValue) {
        float[] embedding = new float[1024];
        embedding[0] = firstValue;
        return embedding;
    }

    private VchordBm25ProjectionService.Projection projection(String titleVector, String contentVector) {
        return new VchordBm25ProjectionService.Projection(
                titleVector, contentVector, VchordBm25ProjectionService.INDEX_VERSION
        );
    }
}
