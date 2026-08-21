package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "g2.rag.baseline.l2", matches = "true")
class RagG2PreBm25BaselineL2Test {

    private static final String JDBC_URL_PROPERTY = "g2.rag.baseline.jdbc-url";
    private static final String ISOLATED_DATABASE = "g2ragbaseline";
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/" + ISOLATED_DATABASE;
    private static final String KB_ID = "00000000-0000-0000-0000-000000002001";
    private static final String DOCUMENT_ID = "00000000-0000-0000-0000-000000002011";
    private static final int CANDIDATE_COUNT = 5_000;
    private static final int ITERATIONS = 20;
    private static final String LEGACY_CONTENT_CANDIDATE_SQL = """
            SELECT id, kb_id, doc_id, content, metadata::text AS metadata,
                   5::double precision AS distance,
                   ROW_NUMBER() OVER (ORDER BY created_at ASC, id ASC) AS rank
            FROM chunk_bge_m3
            WHERE kb_id = ?::uuid
              AND COALESCE(content, '') <> ''
            """;

    @Test
    void capturesIsolatedPreMigrationCandidateScanPlanAndLatency() throws Exception {
        String jdbcUrl = System.getProperty(JDBC_URL_PROPERTY, DEFAULT_JDBC_URL);
        assertTrue(jdbcUrl.contains(ISOLATED_DATABASE), "L2 基线只能连接隔离 g2ragbaseline 数据库");

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "postgres", "")) {
            assertIsolatedDatabase(connection);
            boolean temporaryTableCreated = false;
            try {
                createCandidateTable(connection);
                temporaryTableCreated = true;
                insertCandidates(connection);
                analyzeCandidateTable(connection);

                // Warm the isolated JDBC path without including it in the measured samples.
                readAllLegacyCandidates(connection);

                List<Long> latencyMs = new ArrayList<>();
                for (int index = 0; index < ITERATIONS; index++) {
                    latencyMs.add(readAllLegacyCandidates(connection));
                }
                String queryPlan = explainLegacyCandidateScan(connection);
                long p95LatencyMs = nearestRankP95(latencyMs);
                JsonNode plan = new ObjectMapper().readTree(queryPlan).get(0).path("Plan");
                int plannedRows = plan.path("Plan Rows").asInt();
                int actualRows = plan.path("Actual Rows").asInt();

                assertTrue(containsNodeType(plan, "Seq Scan"));
                assertEquals(CANDIDATE_COUNT, actualRows);
                assertTrue(Math.abs(plannedRows - actualRows) <= CANDIDATE_COUNT / 100,
                        () -> "ANALYZE 后计划估算应接近实际行数: planned=" + plannedRows + ", actual=" + actualRows);
                assertTrue(p95LatencyMs > 0);
                writeReport(connection, latencyMs, p95LatencyMs, queryPlan);
            } finally {
                if (temporaryTableCreated) {
                    dropCandidateTable(connection);
                }
            }
        }
    }

    private void createCandidateTable(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TEMPORARY TABLE chunk_bge_m3 (
                        id UUID PRIMARY KEY,
                        kb_id UUID NOT NULL,
                        doc_id UUID NOT NULL,
                        content TEXT NOT NULL,
                        metadata JSONB NOT NULL,
                        created_at TIMESTAMP NOT NULL
                    )
                    """);
        }
    }

    private void assertIsolatedDatabase(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("SELECT current_database()")) {
            assertTrue(resultSet.next());
            assertEquals(ISOLATED_DATABASE, resultSet.getString(1), "L2 基线只能连接隔离 g2ragbaseline 数据库");
        }
    }

    private void insertCandidates(Connection connection) throws Exception {
        String sql = """
                INSERT INTO chunk_bge_m3 (id, kb_id, doc_id, content, metadata, created_at)
                SELECT md5('g2-pre-bm25-' || item)::uuid,
                       ?::uuid,
                       ?::uuid,
                       CASE item
                           WHEN 1 THEN '标题和正文 BM25 必须在 PostgreSQL 内执行。'
                           WHEN 2 THEN 'selectContentLexicalCandidatesByKbIds 会把全文拉回 JVM。'
                           WHEN 3 THEN 'HARD 上下文过滤必须在 LIMIT 前执行。'
                           WHEN 4 THEN '未获外部许可时 Router 必须拒答。'
                           ELSE '合成词法候选 ' || item || '，用于迁移前全量候选扫描基线。'
                       END,
                       jsonb_build_object('sourceName', 'g2-architecture.md', 'sourceType', 'md',
                           'contentPath', 'G2 RAG Baseline > Candidate ' || item),
                       CURRENT_TIMESTAMP
                FROM generate_series(1, ?) AS item
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, KB_ID);
            statement.setString(2, DOCUMENT_ID);
            statement.setInt(3, CANDIDATE_COUNT);
            assertEquals(CANDIDATE_COUNT, statement.executeUpdate());
        }
    }

    private void analyzeCandidateTable(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("ANALYZE chunk_bge_m3");
        }
    }

    private boolean containsNodeType(JsonNode plan, String nodeType) {
        if (nodeType.equals(plan.path("Node Type").asText())) {
            return true;
        }
        for (JsonNode childPlan : plan.path("Plans")) {
            if (containsNodeType(childPlan, nodeType)) {
                return true;
            }
        }
        return false;
    }

    private long readAllLegacyCandidates(Connection connection) throws Exception {
        long startedAt = System.nanoTime();
        int candidates = 0;
        try (PreparedStatement statement = connection.prepareStatement(LEGACY_CONTENT_CANDIDATE_SQL)) {
            statement.setString(1, KB_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    candidates++;
                }
            }
        }
        assertEquals(CANDIDATE_COUNT, candidates);
        return Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private String explainLegacyCandidateScan(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + LEGACY_CONTENT_CANDIDATE_SQL
        )) {
            statement.setString(1, KB_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private void writeReport(
            Connection connection,
            List<Long> latencyMs,
            long p95LatencyMs,
            String queryPlan
    ) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("datasetId", "g2-pre-bm25-v1");
        report.put("executionMode", "isolated_postgresql_candidate_scan");
        report.put("databaseVersion", databaseVersion(connection));
        report.put("candidateCount", CANDIDATE_COUNT);
        report.put("iterations", ITERATIONS);
        report.put("latencyMs", List.copyOf(latencyMs));
        report.put("p95LatencyMs", p95LatencyMs);
        report.put("queryPlan", queryPlan);
        report.put("limitation", "仅度量迁移前 JDBC 全量候选扫描；不代表端到端 Agent、embedding 或答案生成延迟。");

        Path reportPath = Path.of("target", "rag-eval", "g2-pre-bm25-v1-pre-migration-l2.json");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertTrue(Files.isRegularFile(reportPath));
    }

    private String databaseVersion(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("SELECT version()")) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private long nearestRankP95(List<Long> latencyMs) {
        List<Long> sorted = latencyMs.stream().sorted().toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95D) - 1);
    }

    private void dropCandidateTable(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS pg_temp.chunk_bge_m3");
        }
    }
}
