package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.controller.DocumentController;
import com.kama.jchatmind.exception.GlobalExceptionHandler;
import com.kama.jchatmind.ingestion.G1RuntimePostgresTestConfig;
import com.kama.jchatmind.service.DocumentFacadeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

@SpringJUnitConfig(G1RuntimePostgresTestConfig.class)
@EnabledIfEnvironmentVariable(named = "G1_FILE_COMPENSATION_L2", matches = "true")
class G1FileCompensationRuntimeL2Test {

    private static final long OWNER_ID = 70001L;
    private static final String KB_ID = "00000000-0000-0000-0000-000000007101";
    private static final String KEY = "g1-file-compensation-key";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentFacadeService documentFacadeService;

    @Autowired
    private RequestScopeData requestScopeData;

    private Path storageRoot;

    @BeforeEach
    void setUp() throws Exception {
        storageRoot = Path.of(System.getProperty("g1.storage.dir"));
        Files.createDirectories(storageRoot);
        clearStorage();
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS g1_fail_file_task_insert()");
        jdbcTemplate.execute("DROP TABLE IF EXISTS chunk_bge_m3, ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
        jdbcTemplate.execute("CREATE TABLE jchatmind_user (user_id BIGINT PRIMARY KEY, account VARCHAR(128), username VARCHAR(128), password VARCHAR(128))");
        jdbcTemplate.execute("CREATE TABLE knowledge_base (id UUID PRIMARY KEY, name VARCHAR(128), description VARCHAR(255), metadata JSONB, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbcTemplate.execute("CREATE TABLE document (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, filename VARCHAR(255), filetype VARCHAR(32), size BIGINT, metadata JSONB, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE chunk_bge_m3 (id UUID PRIMARY KEY, doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE)");
        jdbcTemplate.execute("CREATE TABLE ingestion_task (task_id UUID PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE, idempotency_key VARCHAR(128) NOT NULL, task_type VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, attempt_count INTEGER NOT NULL, max_attempts INTEGER NOT NULL, error_summary VARCHAR(500), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, started_at TIMESTAMP, completed_at TIMESTAMP, UNIQUE(owner_id, idempotency_key))");
        jdbcTemplate.update("INSERT INTO jchatmind_user VALUES (?, ?, ?, ?)", OWNER_ID, "g1-file-runtime", "g1", "isolated");
        jdbcTemplate.update("INSERT INTO knowledge_base VALUES (?::uuid, ?, ?, '{}'::jsonb, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", KB_ID, "G1 file runtime", "isolated", OWNER_ID);
        requestScopeData.setUserId(OWNER_ID);
    }

    @AfterEach
    void tearDown() throws Exception {
        clearStorage();
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS g1_fail_file_task_insert()");
        jdbcTemplate.execute("DROP TABLE IF EXISTS chunk_bge_m3, ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
    }

    @Test
    void shouldCompensatePhysicalFileWhenTaskCreationRollsBackAndKeepErrorResponseSanitized() throws Exception {
        jdbcTemplate.execute("CREATE FUNCTION g1_fail_file_task_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled task failure with internal path'; END; $$");
        jdbcTemplate.execute("CREATE TRIGGER g1_fail_file_task_insert BEFORE INSERT ON ingestion_task FOR EACH ROW EXECUTE FUNCTION g1_fail_file_task_insert()");

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DocumentController(documentFacadeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        MockMultipartFile failedFile = new MockMultipartFile(
                "file", "failed.md", "text/markdown", "failed upload".getBytes());
        MvcResult failure = mockMvc.perform(multipart("/api/documents/upload")
                        .file(failedFile)
                        .param("kbId", KB_ID)
                        .header("Idempotency-Key", KEY))
                .andReturn();

        assertThat(failure.getResponse().getStatus()).isBetween(200, 299);
        assertThat(failure.getResponse().getContentAsString())
                .contains("服务器内部错误")
                .doesNotContain(storageRoot.toString())
                .doesNotContain("controlled task failure");
        assertThat(regularFileCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_task", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3", Integer.class)).isZero();

        jdbcTemplate.execute("DROP TRIGGER g1_fail_file_task_insert ON ingestion_task");
        jdbcTemplate.execute("DROP FUNCTION g1_fail_file_task_insert()");

        var retried = documentFacadeService.uploadDocument(
                KB_ID,
                KEY,
                new MockMultipartFile("file", "retry.md", "text/markdown", "retry upload".getBytes())
        );
        assertThat(retried.getDocumentId()).isNotBlank();
        assertThat(retried.getTaskId()).isNotBlank();
        assertThat(regularFileCount()).isEqualTo(1);

        var replay = documentFacadeService.uploadDocument(
                KB_ID,
                KEY,
                new MockMultipartFile("file", "replay.md", "text/markdown", "replay upload".getBytes())
        );
        assertThat(replay.getDocumentId()).isEqualTo(retried.getDocumentId());
        assertThat(replay.getTaskId()).isEqualTo(retried.getTaskId());
        assertThat(regularFileCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_task", Integer.class)).isEqualTo(1);
    }

    @Test
    void shouldRemovePartialPhysicalFileAndDirectoryWhenStreamFailsMidWrite() throws Exception {
        var failingFile = new PartialWriteFailureMultipartFile(
                "partial.md", "text/markdown", "partial content before controlled failure".getBytes());

        assertThatThrownBy(() -> documentFacadeService.uploadDocument(KB_ID, KEY, failingFile))
                .isInstanceOf(com.kama.jchatmind.exception.BizException.class)
                .hasMessage("文件保存失败");

        assertThat(regularFileCount()).isZero();
        assertThat(storageEntryCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_task", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3", Integer.class)).isZero();
    }

    private long regularFileCount() throws Exception {
        try (var paths = Files.walk(storageRoot)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private long storageEntryCount() throws Exception {
        try (var paths = Files.walk(storageRoot)) {
            return paths.filter(path -> !path.equals(storageRoot)).count();
        }
    }

    private void clearStorage() throws Exception {
        if (!Files.exists(storageRoot)) {
            return;
        }
        try (var paths = Files.walk(storageRoot)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(storageRoot))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            throw new IllegalStateException("无法清理测试临时文件", e);
                        }
                    });
        }
    }

    private static final class PartialWriteFailureMultipartFile implements org.springframework.web.multipart.MultipartFile {
        private final String filename;
        private final String contentType;
        private final byte[] payload;

        private PartialWriteFailureMultipartFile(String filename, String contentType, byte[] payload) {
            this.filename = filename;
            this.contentType = contentType;
            this.payload = payload;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public long getSize() {
            return payload.length;
        }

        @Override
        public byte[] getBytes() {
            return payload.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                private int offset;
                private boolean failed;

                @Override
                public int read(byte[] buffer, int off, int len) throws IOException {
                    if (failed) {
                        throw new IOException("controlled partial stream failure");
                    }
                    if (offset == 0) {
                        int count = Math.min(8, Math.min(len, payload.length));
                        System.arraycopy(payload, 0, buffer, off, count);
                        offset = count;
                        return count;
                    }
                    failed = true;
                    throw new IOException("controlled partial stream failure");
                }

                @Override
                public int read() throws IOException {
                    if (offset == 0) {
                        offset = 1;
                        return payload[0] & 0xff;
                    }
                    throw new IOException("controlled partial stream failure");
                }
            };
        }

        @Override
        public void transferTo(File dest) throws IOException {
            throw new IOException("controlled partial stream failure");
        }
    }
}
