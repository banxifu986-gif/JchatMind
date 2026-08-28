package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmarcoZhSampledIsolatedImporterTest {

    private static final String ISOLATED_JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval";
    private static final String JDBC_URL_PROPERTY = "rag.eval.jdbc.url";
    private static final String JDBC_USERNAME_PROPERTY = "rag.eval.jdbc.username";
    private static final String JDBC_PASSWORD_PROPERTY = "rag.eval.jdbc.password";

    @TempDir
    Path temporaryDirectory;

    @Test
    void importsCandidatesWithDeterministicChunkUuidsIntoTheIsolatedDatabase() throws Exception {
        Assumptions.assumeTrue(ISOLATED_JDBC_URL.equals(System.getProperty(JDBC_URL_PROPERTY)),
                "未显式指定 rag-eval 隔离数据库，跳过 mMARCO 导入运行时测试");

        MmarcoZhSampledDatasetFreezer.Candidate first = candidate("p-1", "first passage", "qrels_positive");
        MmarcoZhSampledDatasetFreezer.Candidate second = candidate("p-2", "second passage", "official_hard_negative");

        try (Connection connection = DriverManager.getConnection(
                ISOLATED_JDBC_URL,
                System.getProperty(JDBC_USERNAME_PROPERTY, "rag_eval"),
                System.getProperty(JDBC_PASSWORD_PROPERTY, "")
        )) {
            connection.setAutoCommit(false);
            MmarcoZhSampledIsolatedImporter.ImportResult result = new MmarcoZhSampledIsolatedImporter().importCandidates(
                    connection,
                    new MmarcoZhSampledIsolatedImporter.ImportRequest(
                            "mmarco-zh-sampled-v1",
                            "candidate-manifest-sha-for-test",
                            "900000000003",
                            List.of(
                                    imported(first, 0.01F),
                                    imported(second, 0.02F)
                            )
                    )
            );

            assertEquals(first.runtimeChunkUuid(), result.logicalChunkIdByRuntimeUuid().keySet().stream()
                    .filter(first.runtimeChunkUuid()::equals)
                    .findFirst()
                    .orElseThrow());
            assertEquals(first.logicalChunkId(), result.logicalChunkIdByRuntimeUuid().get(first.runtimeChunkUuid()));
            assertEquals(second.logicalChunkId(), result.logicalChunkIdByRuntimeUuid().get(second.runtimeChunkUuid()));
            assertEquals(first.runtimeChunkUuid(), queryChunkId(connection, first.logicalChunkId()));
            assertEquals(first.logicalChunkId(), queryLogicalChunkId(connection, first.runtimeChunkUuid()));
            assertEquals("rag-eval", queryEvaluationNamespace(connection, first.runtimeChunkUuid()));
            assertTrue(queryChunkCount(connection, result.knowledgeBaseId()) >= 2);
            connection.rollback();
        }
    }

    @Test
    void importsEveryFrozenManifestCandidateWithOneEmbeddingAndBm25Projection() throws Exception {
        Assumptions.assumeTrue(ISOLATED_JDBC_URL.equals(System.getProperty(JDBC_URL_PROPERTY)),
                "未显式指定 rag-eval 隔离数据库，跳过 mMARCO manifest 导入运行时测试");

        MmarcoZhSampledDatasetFreezer.Candidate candidate = candidate("p-manifest", "manifest passage", "qrels_positive");
        MmarcoZhSampledDatasetFreezer.Candidate second = candidate("p-manifest-2", "second manifest passage", "official_hard_negative");
        Path manifestPath = temporaryDirectory.resolve("frozen-manifest.json");
        new ObjectMapper().writeValue(manifestPath.toFile(), new MmarcoZhSampledDatasetFreezer.FrozenManifest(
                "mmarco-zh-sampled-v1",
                "mmarco-zh-sampled-freeze-v1",
                "6d039c4638c0ba3e46a9cb7b498b145e7edc6230",
                "zh",
                "mmarco-zh-deterministic-uuid-v1",
                new MmarcoZhSampledDatasetFreezer.FreezeRequest(1, 0, 1, 0, 20260825L),
                new MmarcoZhSampledDatasetFreezer.SourceSha256("collection-sha", "queries-sha", "qrels-sha", "run-sha"),
                List.of(),
                List.of(new MmarcoZhSampledDatasetFreezer.Query("q-1", "query one")),
                List.of(candidate, second),
                Map.of("q-1", List.of(candidate.logicalChunkId(), second.logicalChunkId()))
        ));
        List<String> embeddingInputs = new java.util.ArrayList<>();
        List<List<String>> embeddingBatches = new java.util.ArrayList<>();
        List<List<String>> projectionInputs = new java.util.ArrayList<>();

        try (Connection connection = DriverManager.getConnection(
                ISOLATED_JDBC_URL,
                System.getProperty(JDBC_USERNAME_PROPERTY, "rag_eval"),
                System.getProperty(JDBC_PASSWORD_PROPERTY, "")
        )) {
            connection.setAutoCommit(false);
            MmarcoZhSampledManifestImporter.ManifestImportResult result = new MmarcoZhSampledManifestImporter()
                    .importManifest(
                            manifestPath,
                            connection,
                            "900000000003",
                            1,
                            contents -> {
                                embeddingInputs.addAll(contents);
                                embeddingBatches.add(List.copyOf(contents));
                                return contents.stream().map(content -> {
                                    float[] embedding = new float[1024];
                                    embedding[0] = 0.1F;
                                    return embedding;
                                }).toList();
                            },
                            (title, content) -> {
                                projectionInputs.add(List.of(title, content));
                                return new MmarcoZhSampledManifestImporter.Bm25Projection("{1:1}", "{1:1}", 1);
                            }
                    );

            assertEquals(sha256(manifestPath), result.candidateManifestSha256());
            assertEquals(List.of(candidate.content(), second.content()), embeddingInputs);
            assertEquals(List.of(List.of(candidate.content()), List.of(second.content())), embeddingBatches);
            assertEquals(
                    List.of(
                            List.of(candidate.passageId(), candidate.content()),
                            List.of(second.passageId(), second.content())
                    ),
                    projectionInputs
            );
            assertEquals(candidate.logicalChunkId(), result.importResult()
                    .logicalChunkIdByRuntimeUuid()
                    .get(candidate.runtimeChunkUuid()));
            assertEquals(second.logicalChunkId(), result.importResult()
                    .logicalChunkIdByRuntimeUuid()
                    .get(second.runtimeChunkUuid()));
            assertEquals(result.candidateManifestSha256(), queryCandidateManifestSha256(connection, candidate.runtimeChunkUuid()));
            assertEquals(result.candidateManifestSha256(), queryCandidateManifestSha256(connection, second.runtimeChunkUuid()));

            MmarcoZhSampledManifestImporter.ManifestImportResult reused = new MmarcoZhSampledManifestImporter()
                    .importManifest(
                            manifestPath,
                            connection,
                            "900000000003",
                            1,
                            contents -> {
                                throw new AssertionError("命中严格复用后不得再次调用 embedding");
                            },
                            (title, content) -> {
                                throw new AssertionError("命中严格复用后不得再次调用 BM25 投影");
                            }
                    );

            assertEquals(result.importResult(), reused.importResult());
            connection.rollback();
        }
    }

    @Test
    void reusesAnExactlyMatchingExistingCandidateImportWithoutNewEmbeddings() throws Exception {
        Assumptions.assumeTrue(ISOLATED_JDBC_URL.equals(System.getProperty(JDBC_URL_PROPERTY)),
                "未显式指定 rag-eval 隔离数据库，跳过 mMARCO 导入运行时测试");

        MmarcoZhSampledDatasetFreezer.Candidate first = candidate("reuse-1", "first reusable passage", "qrels_positive");
        MmarcoZhSampledDatasetFreezer.Candidate second = candidate("reuse-2", "second reusable passage", "official_hard_negative");
        String datasetVersion = "mmarco-zh-sampled-reuse-test";
        String candidateManifestSha256 = "candidate-manifest-sha-for-reuse-test";

        try (Connection connection = DriverManager.getConnection(
                ISOLATED_JDBC_URL,
                System.getProperty(JDBC_USERNAME_PROPERTY, "rag_eval"),
                System.getProperty(JDBC_PASSWORD_PROPERTY, "")
        )) {
            connection.setAutoCommit(false);
            MmarcoZhSampledIsolatedImporter importer = new MmarcoZhSampledIsolatedImporter();
            importer.importCandidates(
                    connection,
                    new MmarcoZhSampledIsolatedImporter.ImportRequest(
                            datasetVersion,
                            candidateManifestSha256,
                            "900000000003",
                            List.of(imported(first, 0.01F), imported(second, 0.02F))
                    )
            );

            Optional<MmarcoZhSampledIsolatedImporter.ImportResult> reused = importer.findExistingImport(
                    connection,
                    datasetVersion,
                    candidateManifestSha256,
                    List.of(first, second)
            );

            assertTrue(reused.isPresent());
            assertEquals(first.logicalChunkId(), reused.orElseThrow().logicalChunkIdByRuntimeUuid()
                    .get(first.runtimeChunkUuid()));
            assertEquals(second.logicalChunkId(), reused.orElseThrow().logicalChunkIdByRuntimeUuid()
                    .get(second.runtimeChunkUuid()));
            connection.rollback();
        }
    }

    @Test
    void rejectsReuseWhenAnExistingChunkContentDiffersFromTheFrozenCandidate() throws Exception {
        Assumptions.assumeTrue(ISOLATED_JDBC_URL.equals(System.getProperty(JDBC_URL_PROPERTY)),
                "未显式指定 rag-eval 隔离数据库，跳过 mMARCO 导入运行时测试");

        MmarcoZhSampledDatasetFreezer.Candidate candidate = candidate(
                "reuse-content-mismatch", "frozen passage", "qrels_positive"
        );
        String datasetVersion = "mmarco-zh-sampled-reuse-content-mismatch-test";
        String candidateManifestSha256 = "candidate-manifest-sha-for-content-mismatch-test";

        try (Connection connection = DriverManager.getConnection(
                ISOLATED_JDBC_URL,
                System.getProperty(JDBC_USERNAME_PROPERTY, "rag_eval"),
                System.getProperty(JDBC_PASSWORD_PROPERTY, "")
        )) {
            connection.setAutoCommit(false);
            MmarcoZhSampledIsolatedImporter importer = new MmarcoZhSampledIsolatedImporter();
            importer.importCandidates(
                    connection,
                    new MmarcoZhSampledIsolatedImporter.ImportRequest(
                            datasetVersion,
                            candidateManifestSha256,
                            "900000000003",
                            List.of(imported(candidate, 0.01F))
                    )
            );
            try (var statement = connection.prepareStatement("""
                    UPDATE chunk_bge_m3
                    SET content = ?
                    WHERE id = CAST(? AS uuid)
                    """)) {
                statement.setString(1, "mutated passage");
                statement.setString(2, candidate.runtimeChunkUuid());
                statement.executeUpdate();
            }

            assertFalse(importer.findExistingImport(
                    connection,
                    datasetVersion,
                    candidateManifestSha256,
                    List.of(candidate)
            ).isPresent());
            connection.rollback();
        }
    }

    @Test
    void rejectsReuseWhenTheExistingKnowledgeBaseMappingVersionDiffers() throws Exception {
        Assumptions.assumeTrue(ISOLATED_JDBC_URL.equals(System.getProperty(JDBC_URL_PROPERTY)),
                "未显式指定 rag-eval 隔离数据库，跳过 mMARCO 导入运行时测试");

        MmarcoZhSampledDatasetFreezer.Candidate candidate = candidate(
                "reuse-mapping-mismatch", "frozen passage", "qrels_positive"
        );
        String datasetVersion = "mmarco-zh-sampled-reuse-mapping-mismatch-test";
        String candidateManifestSha256 = "candidate-manifest-sha-for-mapping-mismatch-test";

        try (Connection connection = DriverManager.getConnection(
                ISOLATED_JDBC_URL,
                System.getProperty(JDBC_USERNAME_PROPERTY, "rag_eval"),
                System.getProperty(JDBC_PASSWORD_PROPERTY, "")
        )) {
            connection.setAutoCommit(false);
            MmarcoZhSampledIsolatedImporter importer = new MmarcoZhSampledIsolatedImporter();
            MmarcoZhSampledIsolatedImporter.ImportResult imported = importer.importCandidates(
                    connection,
                    new MmarcoZhSampledIsolatedImporter.ImportRequest(
                            datasetVersion,
                            candidateManifestSha256,
                            "900000000003",
                            List.of(imported(candidate, 0.01F))
                    )
            );
            try (var statement = connection.prepareStatement("""
                    UPDATE knowledge_base
                    SET metadata = jsonb_set(metadata, '{mappingVersion}', '"invalid"'::jsonb)
                    WHERE id = CAST(? AS uuid)
                    """)) {
                statement.setString(1, imported.knowledgeBaseId());
                statement.executeUpdate();
            }

            assertFalse(importer.findExistingImport(
                    connection,
                    datasetVersion,
                    candidateManifestSha256,
                    List.of(candidate)
            ).isPresent());
            connection.rollback();
        }
    }

    private MmarcoZhSampledIsolatedImporter.ImportedCandidate imported(
            MmarcoZhSampledDatasetFreezer.Candidate candidate,
            float value
    ) {
        float[] embedding = new float[1024];
        embedding[0] = value;
        return new MmarcoZhSampledIsolatedImporter.ImportedCandidate(candidate, embedding, "{1:1}", "{1:1}", 1);
    }

    private MmarcoZhSampledDatasetFreezer.Candidate candidate(String passageId, String content, String sourceType) {
        String logicalChunkId = "mmarco:zh:" + passageId;
        return new MmarcoZhSampledDatasetFreezer.Candidate(
                passageId,
                logicalChunkId,
                UUID.nameUUIDFromBytes(logicalChunkId.getBytes(StandardCharsets.UTF_8)).toString(),
                content,
                sourceType
        );
    }

    private String queryChunkId(Connection connection, String logicalChunkId) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT id::text
                FROM chunk_bge_m3
                WHERE metadata->>'logicalChunkId' = ?
                """)) {
            statement.setString(1, logicalChunkId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private String queryLogicalChunkId(Connection connection, String runtimeChunkUuid) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT metadata->>'logicalChunkId'
                FROM chunk_bge_m3
                WHERE id = CAST(? AS uuid)
                """)) {
            statement.setString(1, runtimeChunkUuid);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private String queryEvaluationNamespace(Connection connection, String runtimeChunkUuid) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT evaluation_namespace
                FROM chunk_bge_m3
                WHERE id = CAST(? AS uuid)
                """)) {
            statement.setString(1, runtimeChunkUuid);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private int queryChunkCount(Connection connection, String knowledgeBaseId) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT count(*)
                FROM chunk_bge_m3
                WHERE kb_id = CAST(? AS uuid)
                """)) {
            statement.setString(1, knowledgeBaseId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private String queryCandidateManifestSha256(Connection connection, String runtimeChunkUuid) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT metadata->>'candidateManifestSha256'
                FROM chunk_bge_m3
                WHERE id = CAST(? AS uuid)
                """)) {
            statement.setString(1, runtimeChunkUuid);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private String sha256(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
