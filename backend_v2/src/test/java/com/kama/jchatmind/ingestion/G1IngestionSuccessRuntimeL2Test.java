package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.mapper.IngestionTaskMapper;
import com.kama.jchatmind.service.DocumentStorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(G1RuntimeIngestionSuccessTestConfig.class)
@EnabledIfEnvironmentVariable(named = "G1_INGESTION_SUCCESS_L2", matches = "true")
class G1IngestionSuccessRuntimeL2Test {

    private static final long OWNER_ID = 70002L;
    private static final String KB_ID = "00000000-0000-0000-0000-000000009101";
    private static final String DOCUMENT_ID = "00000000-0000-0000-0000-000000009111";
    private static final String TASK_ID = "00000000-0000-0000-0000-000000009121";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin rabbitAdmin;

    @Autowired
    private IngestionTaskMapper ingestionTaskMapper;

    @Autowired
    private DefaultIngestionTaskProcessor processor;

    @Autowired
    private DocumentStorageService documentStorageService;

    @Autowired
    private IngestionTaskProgressServiceImpl progressService;

    private Path storageRoot;

    @BeforeEach
    void setUp() throws Exception {
        storageRoot = Path.of(System.getProperty("g1.storage.dir"));
        clearStorage();
        purgeQueues();
        jdbcTemplate.execute("DROP TABLE IF EXISTS chunk_bge_m3, ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("CREATE TABLE jchatmind_user (user_id BIGINT PRIMARY KEY, account VARCHAR(128), username VARCHAR(128), password VARCHAR(128))");
        jdbcTemplate.execute("CREATE TABLE knowledge_base (id UUID PRIMARY KEY, name VARCHAR(128), description VARCHAR(255), metadata JSONB, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE document (id UUID PRIMARY KEY, kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, filename VARCHAR(255), filetype VARCHAR(32), size BIGINT, metadata JSONB, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE chunk_bge_m3 (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE, content TEXT, metadata JSONB, embedding vector(1024), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE ingestion_task (task_id UUID PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE, idempotency_key VARCHAR(128) NOT NULL, task_type VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, attempt_count INTEGER NOT NULL, max_attempts INTEGER NOT NULL, error_summary VARCHAR(500), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, started_at TIMESTAMP, completed_at TIMESTAMP, UNIQUE(owner_id, idempotency_key))");
        jdbcTemplate.update("INSERT INTO jchatmind_user VALUES (?, ?, ?, ?)", OWNER_ID, "g1-ingestion-success", "g1", "isolated");
        jdbcTemplate.update("INSERT INTO knowledge_base VALUES (?::uuid, ?, ?, '{}'::jsonb, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", KB_ID, "G1 success", "isolated", OWNER_ID);
    }

    @AfterEach
    void tearDown() throws Exception {
        purgeQueues();
        jdbcTemplate.execute("DROP TABLE IF EXISTS chunk_bge_m3, ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
        clearStorage();
    }

    @Test
    void shouldProcessMarkdownDirectlyWithRealDatabaseAndEmbedding() throws Exception {
        prepareDocument("guide.md", "md", "# Runtime Guide\nMarkdown success path.\n\n## Rabbit\nReal embedding is stored.\n");
        var task = ingestionTaskMapper.selectById(TASK_ID);
        assertThat(task).isNotNull();
        var document = jdbcTemplate.queryForObject("SELECT metadata->>'filePath' FROM document WHERE id = ?::uuid", String.class, DOCUMENT_ID);
        assertThat(documentStorageService.getFilePath(document)).exists();
        processor.process(task);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3", Integer.class)).isEqualTo(2);
    }

    @Test
    void shouldProcessPdfDirectlyWithRealDatabaseAndEmbedding() throws Exception {
        prepareDocument("guide.pdf", "pdf", twoPagePdf());
        var task = ingestionTaskMapper.selectById(TASK_ID);
        assertStoredFileReadable();

        processor.process(task);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE embedding IS NOT NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE metadata->>'pageNumber' = '1'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE metadata->>'pageNumber' = '2'", Integer.class)).isEqualTo(1);
    }

    @Test
    void shouldIngestMarkdownWithRealEmbeddingAndPersistStructuredChunks() throws Exception {
        prepareDocument("guide.md", "md", "# Runtime Guide\nMarkdown success path.\n\n## Rabbit\nReal embedding is stored.\n");
        publishMainTask();

        awaitStatus("SUCCEEDED", 0);
        assertThat(progressService.latest(TASK_ID)).isPresent()
                .get().extracting(IngestionTaskProgressEvent::status).isEqualTo("SUCCEEDED");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE embedding IS NOT NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE metadata->>'sourceType' = 'md'", Integer.class)).isEqualTo(2);
        assertThat(regularFileCount()).isEqualTo(1);
    }

    @Test
    void shouldExtractStructuredHtmlHeadingsBeforeRealEmbedding() throws Exception {
        prepareDocument("guide.html", "html", "<html><body><h1>HTML Guide</h1><p>HTML overview.</p><h2>Details</h2><p>Structured content.</p></body></html>");
        publishMainTask();

        awaitStatus("SUCCEEDED", 0);
        assertThat(progressService.latest(TASK_ID)).isPresent()
                .get().extracting(IngestionTaskProgressEvent::status).isEqualTo("SUCCEEDED");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE content NOT LIKE '%<'", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE metadata->>'sourceType' = 'html'", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE metadata->>'contentPath' = 'HTML Guide > Details'", Integer.class)).isEqualTo(1);
    }

    @Test
    void shouldIngestPdfPagesWithRealEmbeddingAndPageMetadata() throws Exception {
        prepareDocument("guide.pdf", "pdf", twoPagePdf());
        publishMainTask();

        awaitStatus("SUCCEEDED", 0);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE embedding IS NOT NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE vector_dims(embedding) = 1024", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE metadata->>'sourceType' = 'pdf'", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE metadata->>'pageNumber' = '1'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE metadata->>'pageNumber' = '2'", Integer.class)).isEqualTo(1);
    }

    @Test
    void shouldRetryCorruptPdfThenDeadLetterWithoutPersistentSideEffects() throws Exception {
        prepareDocument("broken.pdf", "pdf", new byte[]{1, 2, 3});

        publishMainTask();
        awaitStatus("RETRYING", 1);
        assertFailureHasNoPersistentChunks();
        assertThat(retryQueueMessageCount()).isGreaterThanOrEqualTo(1);

        publishMainTask();
        awaitStatus("RETRYING", 2);
        assertFailureHasNoPersistentChunks();

        publishMainTask();
        awaitStatus("DEAD_LETTER", 3);
        assertFailureHasNoPersistentChunks();
        assertThat(deadLetterQueueMessageCount()).isGreaterThanOrEqualTo(1);
    }

    private void prepareDocument(String filename, String filetype, String content) throws Exception {
        prepareDocument(filename, filetype, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void prepareDocument(String filename, String filetype, byte[] content) throws Exception {
        Path documentDir = storageRoot.resolve(KB_ID).resolve(DOCUMENT_ID);
        Files.createDirectories(documentDir);
        Path storedFile = documentDir.resolve(filename);
        Files.write(storedFile, content);
        assertThat(Files.isRegularFile(storedFile)).isTrue();
        String relativePath = KB_ID + "/" + DOCUMENT_ID + "/" + filename;
        jdbcTemplate.update("INSERT INTO document VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                DOCUMENT_ID, KB_ID, filename, filetype, content.length, "{\"filePath\":\"" + relativePath + "\"}");
        jdbcTemplate.update("INSERT INTO ingestion_task VALUES (?::uuid, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL)",
                TASK_ID, OWNER_ID, KB_ID, DOCUMENT_ID, "g1-ingestion-success-key", "DOCUMENT_INGESTION", "QUEUED", 0, 3);
    }

    private byte[] twoPagePdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String text : List.of("PDF first page", "PDF second page")) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA, 12);
                    contentStream.newLineAtOffset(72, 720);
                    contentStream.showText(text);
                    contentStream.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private void assertStoredFileReadable() throws Exception {
        String relativePath = jdbcTemplate.queryForObject(
                "SELECT metadata->>'filePath' FROM document WHERE id = ?::uuid",
                String.class,
                DOCUMENT_ID
        );
        Path storedFile = documentStorageService.getFilePath(relativePath);
        assertThat(storedFile).isAbsolute();
        assertThat(storedFile).isEqualTo(storageRoot.resolve(relativePath));
        assertThat(Files.isRegularFile(storedFile)).isTrue();
        try (InputStream inputStream = Files.newInputStream(storedFile)) {
            assertThat(inputStream.read()).isNotEqualTo(-1);
        } catch (IOException e) {
            throw new AssertionError("PDF 存储文件无法读取: " + storedFile, e);
        }
    }

    private void publishMainTask() {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.INGESTION_EXCHANGE,
                RabbitMQConfig.INGESTION_ROUTING_KEY,
                TASK_ID
        );
    }

    private void awaitStatus(String expectedStatus, int expectedAttemptCount) {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            var task = ingestionTaskMapper.selectById(TASK_ID);
            if (task != null && expectedStatus.equals(task.getStatus())
                    && expectedAttemptCount == task.getAttemptCount()) {
                return;
            }
            Thread.onSpinWait();
        }
        var task = ingestionTaskMapper.selectById(TASK_ID);
        throw new AssertionError("任务未达到预期状态: expected=" + expectedStatus + "/" + expectedAttemptCount
                + ", actual=" + (task == null ? "missing" : task.getStatus() + "/" + task.getAttemptCount()
                + ", error=" + task.getErrorSummary()));
    }

    private void assertFailureHasNoPersistentChunks() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_task", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3", Integer.class)).isZero();
        assertThat(regularFileCount()).isEqualTo(1);
    }

    private int retryQueueMessageCount() {
        return rabbitTemplate.execute(channel -> channel.queueDeclarePassive(RabbitMQConfig.INGESTION_RETRY_QUEUE).getMessageCount());
    }

    private int deadLetterQueueMessageCount() {
        return rabbitTemplate.execute(channel -> channel.queueDeclarePassive(RabbitMQConfig.INGESTION_DLQ).getMessageCount());
    }

    private long regularFileCount() throws Exception {
        try (var paths = Files.walk(storageRoot)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private void purgeQueues() {
        for (String queue : List.of(
                RabbitMQConfig.INGESTION_QUEUE,
                RabbitMQConfig.INGESTION_RETRY_QUEUE,
                RabbitMQConfig.INGESTION_DLQ
        )) {
            try {
                rabbitAdmin.purgeQueue(queue, true);
            } catch (Exception ignored) {
            }
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
}
