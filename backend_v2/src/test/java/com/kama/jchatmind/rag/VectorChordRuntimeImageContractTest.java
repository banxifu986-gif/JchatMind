package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

class VectorChordRuntimeImageContractTest {

    private static final Path DOCKERFILE = Path.of("..", "docker", "postgres", "Dockerfile");
    private static final Path COMPOSE_FILE = Path.of("..", "docker-compose.yml");
    private static final Path VCHORD_BM25_PACKAGE = Path.of("..", "docker", "postgres", "vchord-bm25.deb");

    @Test
    void shouldBuildPinnedPgvectorAndVchordBm25Runtime() throws Exception {
        assertThat(Files.exists(DOCKERFILE)).isTrue();
        assertThat(Files.exists(VCHORD_BM25_PACKAGE)).isTrue();
        assertThat(sha256(VCHORD_BM25_PACKAGE))
                .isEqualTo("0631499a47bd9de71e93be481e156e089a2cd68852ac2ecc33f9e0ca4a516ea8");

        String dockerfile = Files.readString(DOCKERFILE).toLowerCase();
        assertThat(dockerfile)
                .contains("from pgvector/pgvector@sha256:1616232566513e94999350040df4a65ac5a01f0d18d6225346ee68714907d79d")
                .contains("vectorchord-bm25 0.3.0 release artifact")
                .contains("copy vchord-bm25.deb /tmp/vchord-bm25.deb")
                .contains("sha256sum -c")
                .contains("dpkg -i /tmp/vchord-bm25.deb")
                .contains("postgresql-14-vchord-bm25");

        String compose = Files.readString(COMPOSE_FILE).toLowerCase();
        assertThat(compose)
                .contains("context: ./docker/postgres")
                .contains("image: jchatmind-postgres-vchord-bm25:0.3.0");
    }

    private String sha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }
}
