package com.kama.jchatmind.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongMemEvalBlindReviewPackageWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesBlindPacketAndPrivateMapSeparatelyAndReusesOnlyIdenticalArtifacts() throws Exception {
        Path reviewerPacketPath = temporaryDirectory.resolve("reviewer").resolve("packet.json");
        Path privateMapPath = temporaryDirectory.resolve("private").resolve("mapping.json");
        LongMemEvalBlindReviewPackageBuilder.Artifacts artifacts = artifacts("prefers tea");

        LongMemEvalBlindReviewPackageWriter.WrittenArtifacts written =
                LongMemEvalBlindReviewPackageWriter.write(reviewerPacketPath, privateMapPath, artifacts);
        byte[] packetBytes = Files.readAllBytes(reviewerPacketPath);
        byte[] mapBytes = Files.readAllBytes(privateMapPath);

        assertThat(written.reviewerPacket()).isEqualTo(reviewerPacketPath);
        assertThat(written.privateMapping()).isEqualTo(privateMapPath);
        assertThat(written.reviewerPacketSha256()).hasSize(64);
        assertThat(written.privateMappingSha256()).hasSize(64);
        assertThat(Files.readString(sidecar(reviewerPacketPath)).trim()).isEqualTo(written.reviewerPacketSha256());
        assertThat(Files.readString(sidecar(privateMapPath)).trim()).isEqualTo(written.privateMappingSha256());
        assertThatThrownBy(() -> LongMemEvalBlindReviewPackageWriter.write(
                reviewerPacketPath, privateMapPath, artifacts("prefers coffee")
        )).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(reviewerPacketPath)).containsExactly(packetBytes);
        assertThat(Files.readAllBytes(privateMapPath)).containsExactly(mapBytes);
        assertThat(LongMemEvalBlindReviewPackageWriter.write(
                reviewerPacketPath, privateMapPath, artifacts
        )).isEqualTo(written);
    }

    @Test
    void rejectsSamePathOrSharedDirectoryForReviewerPacketAndPrivateMapping() {
        Path samePath = temporaryDirectory.resolve("combined.json");
        Path packetPath = temporaryDirectory.resolve("shared").resolve("packet.json");
        Path mappingPath = temporaryDirectory.resolve("shared").resolve("mapping.json");

        assertThatThrownBy(() -> LongMemEvalBlindReviewPackageWriter.write(
                samePath, samePath, artifacts("prefers tea")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LongMemEvalBlindReviewPackageWriter.write(
                packetPath, mappingPath, artifacts("prefers tea")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private LongMemEvalBlindReviewPackageBuilder.Artifacts artifacts(String content) {
        return LongMemEvalBlindReviewPackageBuilder.build(
                "longmemeval-30-v1",
                UUID.fromString("19fcd271-75d1-448d-9426-53f84a947ef1"),
                Set.of("case-1"),
                List.of(new LongMemEvalBlindReviewPackageBuilder.CandidateForReview(
                        "case-1",
                        "candidate-1",
                        "PREFERENCE",
                        content,
                        "medium",
                        "message-1",
                        "The user stated: " + content + ".",
                        "PENDING"
                ))
        );
    }

    private Path sidecar(Path path) {
        return path.resolveSibling(path.getFileName() + ".sha256");
    }
}
