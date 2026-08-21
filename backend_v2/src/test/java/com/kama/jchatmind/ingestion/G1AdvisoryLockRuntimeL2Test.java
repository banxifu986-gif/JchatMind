package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.IngestionTaskMapper;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.service.impl.IngestionTaskServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(G1RuntimePostgresTestConfig.class)
@EnabledIfEnvironmentVariable(named = "G1_ADVISORY_LOCK_L2", matches = "true")
class G1AdvisoryLockRuntimeL2Test {

    private static final long OWNER_ID = 70001L;
    private static final String KB_ID = "00000000-0000-0000-0000-000000007001";
    private static final String DOCUMENT_A = "00000000-0000-0000-0000-000000007011";
    private static final String DOCUMENT_B = "00000000-0000-0000-0000-000000007012";
    private static final String KEY = "g1-advisory-runtime-key";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private IngestionTaskServiceImpl ingestionTaskService;

    @Autowired
    private RequestScopeData requestScopeData;

    @Autowired
    private IngestionTaskMapper ingestionTaskMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS chunk_bge_m3, ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
        jdbcTemplate.execute("CREATE TABLE jchatmind_user (user_id BIGINT PRIMARY KEY, account VARCHAR(128), username VARCHAR(128), password VARCHAR(128))");
        jdbcTemplate.execute("CREATE TABLE knowledge_base (id UUID PRIMARY KEY, name VARCHAR(128), description VARCHAR(255), metadata JSONB, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE document (id UUID PRIMARY KEY, kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, filename VARCHAR(255), filetype VARCHAR(32), size BIGINT, metadata JSONB, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE chunk_bge_m3 (id UUID PRIMARY KEY, doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE)");
        jdbcTemplate.execute("CREATE TABLE ingestion_task (task_id UUID PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE, idempotency_key VARCHAR(128) NOT NULL, task_type VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, attempt_count INTEGER NOT NULL, max_attempts INTEGER NOT NULL, error_summary VARCHAR(500), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, started_at TIMESTAMP, completed_at TIMESTAMP, UNIQUE(owner_id, idempotency_key))");
        jdbcTemplate.update("INSERT INTO jchatmind_user VALUES (?, ?, ?, ?)", OWNER_ID, "g1-runtime", "g1", "isolated");
        jdbcTemplate.update("INSERT INTO knowledge_base VALUES (?::uuid, ?, ?, '{}'::jsonb, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", KB_ID, "G1 runtime", "isolated", OWNER_ID);
        jdbcTemplate.update("INSERT INTO document VALUES (?::uuid, ?::uuid, ?, ?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), (?::uuid, ?::uuid, ?, ?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", DOCUMENT_A, KB_ID, "a.md", "md", 1, DOCUMENT_B, KB_ID, "b.md", "md", 1);
        requestScopeData.setUserId(OWNER_ID);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS chunk_bge_m3, ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
    }

    @Test
    void shouldWaitOnRealAdvisoryLockAndReplayCommittedTask() throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            acquireLock(holder);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<IngestionTask> first = executor.submit(() -> ingestionTaskService
                        .submitDocumentIngestion(KB_ID, DOCUMENT_A, KEY));
                Future<IngestionTask> second = executor.submit(() -> ingestionTaskService
                        .submitDocumentIngestion(KB_ID, DOCUMENT_A, KEY));

                assertThat(waitingAdvisoryLockCount()).isGreaterThanOrEqualTo(1);
                assertThat(first.isDone()).isFalse();
                assertThat(second.isDone()).isFalse();

                holder.commit();
                IngestionTask firstTask = first.get(5, TimeUnit.SECONDS);
                IngestionTask secondTask = second.get(5, TimeUnit.SECONDS);
                assertThat(secondTask.getId()).isEqualTo(firstTask.getId());
                assertThat(secondTask.getDocumentId()).isEqualTo(DOCUMENT_A);
                assertThat(ingestionTaskMapper.selectByOwnerIdAndIdempotencyKey(OWNER_ID, KEY).getId())
                        .isEqualTo(firstTask.getId());
                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_task", Integer.class))
                        .isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }

        assertThatThrownBy(() -> ingestionTaskService
                .submitDocumentIngestion(KB_ID, DOCUMENT_B, KEY))
                .isInstanceOf(BizException.class)
                .hasMessage("幂等键已用于其他资源");
    }

    @Test
    void shouldReleaseLockAndDatabaseStateAfterTaskInsertRollback() throws Exception {
        jdbcTemplate.execute("CREATE FUNCTION g1_fail_ingestion_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled ingestion failure'; END; $$");
        jdbcTemplate.execute("CREATE TRIGGER g1_fail_ingestion_insert BEFORE INSERT ON ingestion_task FOR EACH ROW EXECUTE FUNCTION g1_fail_ingestion_insert()");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<IngestionTask> failed = executor.submit(() -> ingestionTaskService
                    .submitDocumentIngestion(KB_ID, DOCUMENT_A, KEY));
            ExecutionException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> failed.get(5, TimeUnit.SECONDS), ExecutionException.class);
            assertThat(exception).isNotNull();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_task", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }

        jdbcTemplate.execute("DROP TRIGGER g1_fail_ingestion_insert ON ingestion_task");
        jdbcTemplate.execute("DROP FUNCTION g1_fail_ingestion_insert()");
        IngestionTask retried = ingestionTaskService.submitDocumentIngestion(KB_ID, DOCUMENT_A, KEY);
        assertThat(retried.getId()).isNotBlank();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_task", Integer.class)).isEqualTo(1);
    }

    private void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, OWNER_ID + ":" + KEY);
            statement.executeQuery().close();
        }
    }

    private int waitingAdvisoryLockCount() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        int count = 0;
        while (System.nanoTime() < deadline) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event = 'advisory' AND query ILIKE '%pg_advisory_xact_lock%'",
                    Integer.class
            );
            if (count > 0) {
                return count;
            }
            Thread.onSpinWait();
        }
        return count;
    }
}
