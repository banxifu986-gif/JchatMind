package com.kama.jchatmind.governance;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryGitBoundaryContractTest {

    @Test
    void shouldIgnoreLocalDatasetsGeneratedReportsAndEnvironmentFiles() throws Exception {
        assertThat(isIgnored("datasets/public-benchmark/.boundary-probe.json")).isTrue();
        assertThat(isIgnored("backend_v2/target/rag-eval/.boundary-probe.json")).isTrue();
        assertThat(isIgnored(".env")).isTrue();
        assertThat(isIgnored("README.md")).isFalse();
    }

    private boolean isIgnored(String repositoryRelativePath) throws Exception {
        Process process = new ProcessBuilder(
                "git", "check-ignore", "--quiet", "--no-index", "--", repositoryRelativePath
        )
                .directory(repositoryRoot().toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("git check-ignore did not finish within 10 seconds");
        }
        if (process.exitValue() > 1) {
            throw new IllegalStateException("git check-ignore failed with exit code " + process.exitValue());
        }
        return process.exitValue() == 0;
    }

    private Path repositoryRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Git repository root was not found");
    }
}
