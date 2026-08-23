package com.kama.jchatmind.rag;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "g2.vchord.runtime.image.l2", matches = "true")
class VectorChordRuntimeImageL2Test {

    private static final String IMAGE = "g2-vchord-runtime-contract:0.3.0";
    private static final String CONTAINER = "g2-vchord-runtime-contract-"
            + UUID.randomUUID().toString().substring(0, 8);
    private static final String DATABASE = "g2vchordruntime";
    private static final String POSTGRES_PASSWORD = UUID.randomUUID().toString();
    private static boolean containerStarted;

    @BeforeAll
    static void setUpRuntime() {
        run(List.of("docker", "build", "--pull=false", "--tag", IMAGE, "../docker/postgres"));
        run(List.of(
                "docker", "run", "--rm", "-d", "--name", CONTAINER,
                "-e", "POSTGRES_PASSWORD=" + POSTGRES_PASSWORD,
                "-e", "POSTGRES_DB=" + DATABASE,
                IMAGE
        ));
        containerStarted = true;
        waitForPostgres();
    }

    @AfterAll
    static void tearDownRuntime() {
        if (containerStarted) {
            run(List.of("docker", "stop", CONTAINER));
        }
    }

    @Test
    void shouldBuildAndLoadVchordBm25InTemporaryPostgresRuntime() {
        String extension = run(List.of(
                "docker", "exec", CONTAINER,
                "psql", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", DATABASE,
                "-c", "CREATE EXTENSION vchord_bm25; SELECT extversion FROM pg_extension WHERE extname = 'vchord_bm25'"
        ));

        assertThat(extension.trim()).isEqualTo("0.3.0");
    }

    private static void waitForPostgres() {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        AssertionError lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (!"postgres".equals(run(List.of(
                        "docker", "exec", CONTAINER, "cat", "/proc/1/comm"
                )).trim())) {
                    throw new AssertionError("临时 VectorChord 运行时仍在 PostgreSQL 初始化阶段");
                }
                run(List.of("docker", "exec", CONTAINER, "pg_isready", "-U", "postgres", "-d", DATABASE));
                return;
            } catch (AssertionError failure) {
                lastFailure = failure;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("等待临时 VectorChord 运行时被中断", exception);
                }
            }
        }
        throw new AssertionError("临时 VectorChord 运行时未在 30 秒内就绪", lastFailure);
    }

    private static String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new AssertionError("VectorChord 运行时命令失败: " + String.join(" ", command) + "\n" + output);
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("VectorChord 运行时命令被中断", exception);
        } catch (Exception exception) {
            throw new AssertionError("无法执行 VectorChord 运行时命令: " + String.join(" ", command), exception);
        }
    }
}
