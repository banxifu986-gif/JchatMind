package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MmarcoZhSampledManifestImporterTest {

    @Test
    void pairsEachCandidateWithItsMatchingBatchEmbedding() {
        List<MmarcoZhSampledDatasetFreezer.Candidate> candidates = List.of(
                candidate("first", "first content"),
                candidate("second", "second content")
        );
        AtomicReference<List<String>> receivedTexts = new AtomicReference<>();

        List<MmarcoZhSampledIsolatedImporter.ImportedCandidate> imported = new MmarcoZhSampledManifestImporter()
                .toImportedCandidates(
                        candidates,
                        2,
                        texts -> {
                            receivedTexts.set(List.copyOf(texts));
                            return List.of(new float[]{1F}, new float[]{2F});
                        },
                        (passageId, content) -> new MmarcoZhSampledManifestImporter.Bm25Projection(
                                "title-" + passageId,
                                "content-" + content,
                                1
                        )
                );

        assertThat(receivedTexts.get()).containsExactly("first content", "second content");
        assertThat(imported).extracting(item -> item.embedding()[0]).containsExactly(1F, 2F);
        assertThat(imported).extracting(item -> item.contentBm25Vector())
                .containsExactly("content-first content", "content-second content");
    }

    @Test
    void requestsEmbeddingsInBoundedOrderedBatches() {
        List<MmarcoZhSampledDatasetFreezer.Candidate> candidates = List.of(
                candidate("first", "first content"),
                candidate("second", "second content")
        );
        List<List<String>> batches = new CopyOnWriteArrayList<>();

        List<MmarcoZhSampledIsolatedImporter.ImportedCandidate> imported = new MmarcoZhSampledManifestImporter()
                .toImportedCandidates(
                        candidates,
                        1,
                        texts -> {
                            batches.add(List.copyOf(texts));
                            return List.of(new float[]{(float) batches.size()});
                        },
                        (passageId, content) -> new MmarcoZhSampledManifestImporter.Bm25Projection(
                                "title-" + passageId,
                                "content-" + content,
                                1
                        )
                );

        assertThat(batches).containsExactly(List.of("first content"), List.of("second content"));
        assertThat(imported).extracting(item -> item.embedding()[0]).containsExactly(1F, 2F);
    }

    private MmarcoZhSampledDatasetFreezer.Candidate candidate(String passageId, String content) {
        return new MmarcoZhSampledDatasetFreezer.Candidate(
                passageId,
                "mmarco:zh:" + passageId,
                passageId + "-uuid",
                content,
                "verified_hard_negative"
        );
    }
}
