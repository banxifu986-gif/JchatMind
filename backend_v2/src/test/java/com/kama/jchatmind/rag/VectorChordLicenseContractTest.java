package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VectorChordLicenseContractTest {

    private static final Path THIRD_PARTY_NOTICES = Path.of("..", "THIRD_PARTY_NOTICES.md");

    @Test
    void shouldDeclareElasticLicenseForPinnedVchordBm25RuntimeArtifact() throws Exception {
        assertThat(Files.exists(THIRD_PARTY_NOTICES)).isTrue();

        String notices = Files.readString(THIRD_PARTY_NOTICES);
        assertThat(notices)
                .contains("postgresql-14-vchord-bm25")
                .contains("0.3.0")
                .contains("Elastic-2.0")
                .contains("https://github.com/tensorchord/VectorChord-bm25/")
                .contains("Tensorchord <support@tensorchord.ai>")
                .contains("0631499a47bd9de71e93be481e156e089a2cd68852ac2ecc33f9e0ca4a516ea8");
    }
}
