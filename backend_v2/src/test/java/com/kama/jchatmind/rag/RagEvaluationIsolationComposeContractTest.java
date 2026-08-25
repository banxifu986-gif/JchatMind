package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvaluationIsolationComposeContractTest {

    private static final Path COMPOSE_PATH = Path.of("..", "docker-compose.rag-eval.yml");
    private static final Path INIT_PATH = Path.of("..", "rag-eval", "init", "001-extensions.sql");
    private static final Path SCHEMA_PATH = Path.of("..", "rag-eval", "init", "002-evaluation-schema.sql");
    private static final Path UPLOAD_DIRECTORY = Path.of("..", "rag-eval", "uploads");

    @Test
    void provisionsAnIsolatedVectorChordDatabaseAndUploadDirectory() throws Exception {
        assertTrue(Files.exists(COMPOSE_PATH));
        assertTrue(Files.exists(INIT_PATH));
        assertTrue(Files.exists(SCHEMA_PATH));
        assertTrue(Files.isDirectory(UPLOAD_DIRECTORY));

        String compose = Files.readString(COMPOSE_PATH);
        String initialization = Files.readString(INIT_PATH);
        String schema = Files.readString(SCHEMA_PATH);

        assertTrue(compose.contains("container_name: jchatmind-rag-eval-postgres"));
        assertTrue(compose.contains("127.0.0.1:55432:5432"));
        assertTrue(compose.contains("rag_eval_postgres_data"));
        assertTrue(compose.contains("POSTGRES_HOST_AUTH_METHOD: trust"));
        assertTrue(compose.contains("./rag-eval/uploads:/var/lib/jchatmind/rag-eval/uploads"));
        assertFalse(compose.contains("jchatmind_postgres_data"));
        assertTrue(initialization.contains("CREATE EXTENSION IF NOT EXISTS vector;"));
        assertTrue(initialization.contains("CREATE EXTENSION IF NOT EXISTS vchord_bm25;"));
        assertTrue(schema.contains("CREATE SCHEMA IF NOT EXISTS rag_eval;"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS public.knowledge_base"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS public.document"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS public.chunk_bge_m3"));
        assertTrue(schema.contains("evaluation_namespace TEXT NOT NULL DEFAULT 'rag-eval'"));
        assertTrue(schema.contains("embedding VECTOR(1024) NOT NULL"));
        assertTrue(schema.contains("idx_chunk_bge_m3_title_bm25"));
        assertTrue(schema.contains("idx_chunk_bge_m3_content_bm25"));
    }
}
