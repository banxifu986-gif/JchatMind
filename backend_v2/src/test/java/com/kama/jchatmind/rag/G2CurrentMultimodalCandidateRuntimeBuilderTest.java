package com.kama.jchatmind.rag;

import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class G2CurrentMultimodalCandidateRuntimeBuilderTest {

    private static final String MANIFEST_PATH =
            "rag-eval/datasets/manifests/g2-current-multimodal-v1.json";

    @Test
    void attachesEmbeddingsAndBm25ProjectionsToSortedCandidateChunks() throws Exception {
        G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile =
                G2CurrentMultimodalCandidateFixtureLoader.load(MANIFEST_PATH);
        List<G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow> chunkRows =
                profile.assetImportPlan().chunkRows();
        List<float[]> embeddings = List.of(embedding(1), embedding(2));
        List<VchordBm25ProjectionService.Projection> projections = List.of(
                projection("{1:1}", "{2:1}"),
                projection("{3:1}", "{4:1}")
        );

        G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan prepared =
                G2CurrentMultimodalCandidateRuntimeBuilder.build(profile, embeddings, projections);

        assertThat(prepared.assetImportPlan()).isEqualTo(profile.assetImportPlan());
        assertThat(prepared.candidateOnly()).isTrue();
        assertThat(prepared.chunks()).hasSize(chunkRows.size());
        for (int index = 0; index < chunkRows.size(); index++) {
            G2CurrentMultimodalCandidateRuntimeBuilder.RuntimeChunk runtimeChunk = prepared.chunks().get(index);
            assertThat(runtimeChunk.chunk()).isEqualTo(chunkRows.get(index));
            assertThat(runtimeChunk.embedding()).containsExactly(embeddings.get(index));
            assertThat(runtimeChunk.projection()).isEqualTo(projections.get(index));
        }
    }

    @Test
    void rejectsNonCandidatePlansAndIncompleteRuntimeInputs() throws Exception {
        G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile =
                G2CurrentMultimodalCandidateFixtureLoader.load(MANIFEST_PATH);
        G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan = profile.assetImportPlan();
        G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan nonCandidatePlan =
                new G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan(
                        plan.knowledgeBaseId(),
                        plan.knowledgeBaseMetadata(),
                        false,
                        plan.documentRows(),
                        plan.chunkRows(),
                        plan.assetRows(),
                        plan.assetChunkRelations(),
                        plan.chunkAssetMetadataByLogicalId()
                );
        G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile nonCandidateProfile =
                new G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile(
                        profile.datasetId(),
                        profile.status(),
                        profile.manifestSha256(),
                        profile.caseSha256(),
                        profile.corpusSha256(),
                        profile.cases(),
                        profile.assets(),
                        nonCandidatePlan
                );

        assertThatThrownBy(() -> G2CurrentMultimodalCandidateRuntimeBuilder.build(
                nonCandidateProfile, List.of(embedding(1), embedding(2)),
                List.of(projection("{1:1}", "{2:1}"), projection("{3:1}", "{4:1}"))
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> G2CurrentMultimodalCandidateRuntimeBuilder.build(
                profile, List.of(embedding(1)), List.of(projection("{1:1}", "{2:1}"))
        )).isInstanceOf(IllegalArgumentException.class);

        List<float[]> invalidEmbeddings = new ArrayList<>(List.of(embedding(1), embedding(2)));
        invalidEmbeddings.set(1, new float[3]);
        assertThatThrownBy(() -> G2CurrentMultimodalCandidateRuntimeBuilder.build(
                profile, invalidEmbeddings,
                List.of(projection("{1:1}", "{2:1}"), projection("{3:1}", "{4:1}"))
        )).isInstanceOf(IllegalArgumentException.class);
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
