package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DmiImageRagCandidateFixtureLoaderTest {

    private static final Path MANIFEST = Path.of(
            "src", "test", "resources",
            "rag-eval", "datasets", "manifests", "dmi-image-rag-v1.json"
    );

    @Test
    void shouldLoadCandidateFixtureAndValidateGoldAssetsAndRoutes() throws Exception {
        DmiImageRagCandidateFixtureLoader.DmiCandidateProfile profile =
                DmiImageRagCandidateFixtureLoader.load("rag-eval/datasets/manifests/dmi-image-rag-v1.json");

        assertThat(profile.cases()).hasSize(8);
        assertThat(profile.cases()).allMatch(item -> "pending".equals(item.reviewStatus()));

        List<DmiImageRagCandidateFixtureLoader.DmiCandidateAsset> imageAssets = profile.assets().stream()
                .filter(item -> "IMAGE".equals(item.assetType()))
                .toList();
        assertThat(imageAssets).hasSize(6);
        assertThat(imageAssets).allMatch(item -> item.pageNumber() == 1);
        assertThat(imageAssets).allMatch(item -> item.bbox().size() == 4);
        assertThat(imageAssets)
                .extracting(DmiImageRagCandidateFixtureLoader.DmiCandidateAsset::sourceDocumentId)
                .containsExactlyInAnyOrder("dmi-order-architecture", "dmi-delivery-screenshot", "dmi-service-dependency-table", "dmi-service-diagram", "dmi-arch-diagram", "dmi-dep-table");

        assertThat(profile.manifestSha256()).isEqualTo(sha256(MANIFEST));
        assertThat(profile.cases())
                .filteredOn(item -> "no_visual_asset".equals(item.queryType()))
                .allMatch(item -> item.shouldAbstain());
        assertThat(profile.cases())
                .filteredOn(item -> "permission_isolation".equals(item.queryType()))
                .allMatch(item -> "ABSTAIN".equals(item.expectedRoute()));
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}