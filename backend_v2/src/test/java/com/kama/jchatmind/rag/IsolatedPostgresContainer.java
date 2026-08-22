package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class IsolatedPostgresContainer {

    private static final Pattern CONTENT_PATH_SEPARATOR = Pattern.compile("\\s*>\\s*");

    private final String containerName;
    private final String databaseName;
    private final String expectedImageId;

    IsolatedPostgresContainer(String containerName, String databaseName, String expectedImageId) {
        this.containerName = containerName;
        this.databaseName = databaseName;
        this.expectedImageId = expectedImageId;
    }

    void assertIsolation() {
        assertEquals(expectedImageId, run(List.of(
                "docker", "inspect", containerName, "--format", "{{.Image}}"
        ), null).trim(), "PoC 容器镜像不符合预期");
        assertEquals(databaseName, sql("SELECT current_database()").trim(), "PoC 只能连接指定隔离数据库");
    }

    String sql(String sql) {
        return run(List.of(
                "docker", "exec", containerName,
                "psql", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", databaseName, "-c", sql
        ), null);
    }

    String sqlCommands(String... statements) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "exec", containerName,
                "psql", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", databaseName
        ));
        for (String statement : statements) {
            command.add("-c");
            command.add(statement);
        }
        return run(command, null);
    }

    String dumpTable(String tableName) {
        return run(List.of(
                "docker", "exec", containerName,
                "pg_dump", "-U", "postgres", "-d", databaseName,
                "--no-owner", "--no-privileges", "--table=" + tableName
        ), null);
    }

    void restore(String dump) {
        run(List.of(
                "docker", "exec", "-i", containerName,
                "psql", "-q", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", databaseName
        ), dump);
    }

    static String normalizeContentPath(String contentPath) {
        return CONTENT_PATH_SEPARATOR.matcher(contentPath.trim()).replaceAll(" > ");
    }

    String imageId() {
        return expectedImageId;
    }

    void writeEvidenceReport(String reportFileName, Map<String, Object> evidence) {
        Path reportPath = Path.of("target", "rag-eval", reportFileName);
        try {
            Files.createDirectories(reportPath.getParent());
            Files.writeString(
                    reportPath,
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence)
            );
        } catch (Exception exception) {
            throw new AssertionError("无法写入隔离 PoC 证据报告: " + reportPath, exception);
        }
    }

    private String run(List<String> command, String standardInput) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            try (OutputStream outputStream = process.getOutputStream()) {
                if (standardInput != null) {
                    outputStream.write(standardInput.getBytes(StandardCharsets.UTF_8));
                }
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new AssertionError("隔离 PoC 命令失败: " + String.join(" ", command) + "\n" + output);
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("隔离 PoC 命令被中断", exception);
        } catch (Exception exception) {
            throw new AssertionError("无法执行隔离 PoC 命令: " + String.join(" ", command), exception);
        }
    }

    private void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
