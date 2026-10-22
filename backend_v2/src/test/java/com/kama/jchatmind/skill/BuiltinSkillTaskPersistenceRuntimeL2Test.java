package com.kama.jchatmind.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.JwtUtil;
import com.kama.jchatmind.mapper.BuiltinSkillTaskMapper;
import com.kama.jchatmind.model.entity.BuiltinSkillExecutionTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "g3.skill.task.persistence.l2", matches = "true")
class BuiltinSkillTaskPersistenceRuntimeL2Test {

    private static final String JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval";
    private static final String DB_USER = "rag_eval";
    private static final String AGENT_ID = "00000000-0000-0000-0000-000000000042";
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void persistsAndReplaysIdempotentTaskSnapshotsThroughTheProductionMapper() throws Exception {
        String schema = "g3_skill_task_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);
        AnnotationConfigApplicationContext context = null;
        try {
            createPrerequisitesAndApplyTaskMigration(schema);
            context = newApplicationContext(schema);
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            BuiltinSkillTaskMapper mapper = context.getBean(BuiltinSkillTaskMapper.class);
            BuiltinSkillExecutionService executionService = context.getBean(BuiltinSkillExecutionService.class);
            CountDownLatch bothPrepared = new CountDownLatch(2);
            CountDownLatch releaseSubmissions = new CountDownLatch(1);
            BuiltinSkillTaskService service = new BuiltinSkillTaskService(
                    executionService, mapper, new ObjectMapper()
            );
            when(executionService.prepare(anyLong(), anyString(), anyString(), anyString(), anyMap()))
                    .thenAnswer(invocation -> {
                        Map<String, Object> request = invocation.getArgument(4);
                        if ("并发幂等请求".equals(request.get("question"))) {
                            bothPrepared.countDown();
                            if (!releaseSubmissions.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out waiting for concurrent submissions");
                            }
                        }
                        return prepared(request);
                    });

            assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
                    .isEqualTo("jchatmind_rag_eval");
            assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(DB_USER);
            assertThat(jdbcTemplate.queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);

            Map<String, Object> input = Map.of("question", "对比两个缓存方案", "kbIds", List.of("kb-1"));
            BuiltinSkillTaskService.SubmissionResult first = service.submit(
                    42L, AGENT_ID, SESSION_ID, "technical-decision-comparison", "request-key-1", input
            );
            BuiltinSkillTaskService.SubmissionResult replay = service.submit(
                    42L, AGENT_ID, SESSION_ID, "technical-decision-comparison", "request-key-1", input
            );

            assertThat(first.taskId()).isEqualTo(replay.taskId());
            assertThat(first.status()).isEqualTo("PENDING");
            assertThat(first.replayed()).isFalse();
            assertThat(replay.replayed()).isTrue();
            BuiltinSkillExecutionTask stored = mapper.selectByRequesterAndIdempotencyKey(42L, "request-key-1");
            assertThat(stored.getTaskId()).isEqualTo(first.taskId());
            assertThat(stored.getSkillVersion()).isEqualTo("v1");
            assertThat(stored.getRequestSha256()).matches("[0-9a-f]{64}");
            assertThat(stored.getInputSnapshot()).contains("对比两个缓存方案", "kb-1");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM builtin_skill_execution_task", Integer.class
            )).isEqualTo(1);

            assertThat(mapper.selectDispatchableTaskIds(10)).contains(first.taskId());
            CountDownLatch leaseRaceReady = new CountDownLatch(2);
            CountDownLatch releaseLeaseRace = new CountDownLatch(1);
            var leaseExecutor = Executors.newFixedThreadPool(2);
            try {
                var firstLease = leaseExecutor.submit(() -> {
                    leaseRaceReady.countDown();
                    releaseLeaseRace.await();
                    return mapper.acquireDispatchLease(first.taskId(), 60);
                });
                var secondLease = leaseExecutor.submit(() -> {
                    leaseRaceReady.countDown();
                    releaseLeaseRace.await();
                    return mapper.acquireDispatchLease(first.taskId(), 60);
                });
                assertThat(leaseRaceReady.await(10, TimeUnit.SECONDS)).isTrue();
                releaseLeaseRace.countDown();
                assertThat(List.of(
                        firstLease.get(10, TimeUnit.SECONDS), secondLease.get(10, TimeUnit.SECONDS)
                )).containsExactlyInAnyOrder(0, 1);
            } finally {
                releaseLeaseRace.countDown();
                leaseExecutor.shutdownNow();
            }
            assertThat(mapper.selectDispatchableTaskIds(10)).doesNotContain(first.taskId());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT dispatch_attempt_count FROM builtin_skill_execution_task WHERE task_id = ?::uuid",
                    Integer.class,
                    first.taskId()
            )).isEqualTo(1);
            jdbcTemplate.update(
                    "UPDATE builtin_skill_execution_task SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                            + "WHERE task_id = ?::uuid",
                    first.taskId()
            );
            assertThat(mapper.selectDispatchableTaskIds(10)).contains(first.taskId());
            BuiltinSkillTaskPublisher taskPublisher = mock(BuiltinSkillTaskPublisher.class);
            BuiltinSkillTaskDispatchService dispatchService = new BuiltinSkillTaskDispatchService(
                    mapper, taskPublisher
            );
            dispatchService.dispatchPendingTasks();
            dispatchService.dispatchPendingTasks();
            verify(taskPublisher).publish(first.taskId());
            verifyNoMoreInteractions(taskPublisher);
            assertThat(mapper.selectDispatchableTaskIds(10)).doesNotContain(first.taskId());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT dispatch_attempt_count FROM builtin_skill_execution_task WHERE task_id = ?::uuid",
                    Integer.class,
                    first.taskId()
            )).isEqualTo(2);

            var executor = Executors.newFixedThreadPool(2);
            try {
                Map<String, Object> concurrentInput = Map.of(
                        "question", "并发幂等请求", "kbIds", List.of("kb-1")
                );
                var firstSubmit = executor.submit(() -> service.submit(
                        42L, AGENT_ID, SESSION_ID, "technical-decision-comparison", "request-key-race", concurrentInput
                ));
                var secondSubmit = executor.submit(() -> service.submit(
                        42L, AGENT_ID, SESSION_ID, "technical-decision-comparison", "request-key-race", concurrentInput
                ));
                assertThat(bothPrepared.await(10, TimeUnit.SECONDS)).isTrue();
                releaseSubmissions.countDown();
                BuiltinSkillTaskService.SubmissionResult firstRace = firstSubmit.get(10, TimeUnit.SECONDS);
                BuiltinSkillTaskService.SubmissionResult secondRace = secondSubmit.get(10, TimeUnit.SECONDS);

                assertThat(firstRace.taskId()).isEqualTo(secondRace.taskId());
                assertThat(List.of(firstRace.replayed(), secondRace.replayed()))
                        .containsExactlyInAnyOrder(false, true);
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM builtin_skill_execution_task", Integer.class
                )).isEqualTo(2);
            } finally {
                releaseSubmissions.countDown();
                executor.shutdownNow();
            }

            BuiltinSkillTaskService.SubmissionResult lifecycleTask = service.submit(
                    42L, AGENT_ID, SESSION_ID, "technical-decision-comparison", "request-key-lifecycle",
                    Map.of("question", "执行状态迁移", "kbIds", List.of("kb-1"))
            );
            assertThat(mapper.claimExecutionTask(lifecycleTask.taskId(), 300)).isEqualTo(1);
            BuiltinSkillExecutionTask running = mapper.selectByTaskId(lifecycleTask.taskId());
            assertThat(running.getStatus()).isEqualTo("RUNNING");
            assertThat(running.getAttemptCount()).isEqualTo(1);
            assertThat(mapper.claimExecutionTask(lifecycleTask.taskId(), 300)).isZero();
            assertThat(mapper.retryExecutionTask(
                    lifecycleTask.taskId(), 1, "SyntheticFailure", "可重试失败", 30
            )).isEqualTo(1);
            assertThat(mapper.selectByTaskId(lifecycleTask.taskId()).getStatus()).isEqualTo("RETRYING");
            jdbcTemplate.update(
                    "UPDATE builtin_skill_execution_task SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                            + "WHERE task_id = ?::uuid",
                    lifecycleTask.taskId()
            );
            assertThat(mapper.claimExecutionTask(lifecycleTask.taskId(), 300)).isEqualTo(1);
            assertThat(mapper.selectByTaskId(lifecycleTask.taskId()).getAttemptCount()).isEqualTo(2);
            assertThat(mapper.completeExecutionTask(
                    lifecycleTask.taskId(), 1, "{\"conclusion\":\"stale\"}"
            )).isZero();
            assertThat(mapper.completeExecutionTask(
                    lifecycleTask.taskId(), 2,
                    "{\"conclusion\":\"done\",\"evidence\":[{\"chunkId\":\"c1\",\"kbId\":\"kb-1\"}]}"
            )).isEqualTo(1);
            BuiltinSkillExecutionTask completed = mapper.selectByTaskId(lifecycleTask.taskId());
            assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
            assertThat(completed.getProgress()).isEqualTo((short) 100);
            assertThat(completed.getResultJson()).contains("done", "c1");

            BuiltinSkillTaskService.SubmissionResult expiredTask = service.submit(
                    42L, AGENT_ID, SESSION_ID, "technical-decision-comparison", "request-key-expired",
                    Map.of("question", "租约恢复", "kbIds", List.of("kb-1"))
            );
            assertThat(mapper.claimExecutionTask(expiredTask.taskId(), 1)).isEqualTo(1);
            jdbcTemplate.update(
                    "UPDATE builtin_skill_execution_task SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                            + "WHERE task_id = ?::uuid",
                    expiredTask.taskId()
            );
            assertThat(mapper.recoverExpiredExecutionTasks(30)).isEqualTo(1);
            BuiltinSkillExecutionTask recovered = mapper.selectByTaskId(expiredTask.taskId());
            assertThat(recovered.getStatus()).isEqualTo("RETRYING");
            assertThat(recovered.getFailureType()).isEqualTo("ExecutionLeaseExpired");

            BuiltinSkillTaskService.SubmissionResult finalFailureTask = service.submit(
                    42L, AGENT_ID, SESSION_ID, "technical-decision-comparison", "request-key-dead-letter",
                    Map.of("question", "DLQ 状态", "kbIds", List.of("kb-1"))
            );
            jdbcTemplate.update(
                    "UPDATE builtin_skill_execution_task SET max_attempts = 1 WHERE task_id = ?::uuid",
                    finalFailureTask.taskId()
            );
            assertThat(mapper.claimExecutionTask(finalFailureTask.taskId(), 300)).isEqualTo(1);
            assertThat(mapper.deadLetterExecutionTask(
                    finalFailureTask.taskId(), 1, "SyntheticFailure", "达到最大尝试次数"
            )).isEqualTo(1);
            assertThat(mapper.selectByTaskId(finalFailureTask.taskId()).getStatus()).isEqualTo("DEAD_LETTER");

            assertThatThrownBy(() -> service.submit(
                    42L,
                    AGENT_ID,
                    SESSION_ID,
                    "technical-decision-comparison",
                    "request-key-1",
                    Map.of("question", "改成不同问题", "kbIds", List.of("kb-1"))
            )).hasMessage("幂等键已用于不同任务请求");
            assertThatThrownBy(() -> service.getOwnedTask(first.taskId(), 84L))
                    .hasMessage("任务不存在或无权访问");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM builtin_skill_execution_task", Integer.class
            )).isEqualTo(5);
        } finally {
            if (context != null) {
                context.close();
            }
            dropSchema(schema);
        }
        assertSchemaDoesNotExist(schema);
    }

    private void createSchema(String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, DB_USER, "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    private void createPrerequisitesAndApplyTaskMigration(String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                JDBC_URL + "?currentSchema=" + schema, DB_USER, "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE jchatmind_user (user_id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE agent (id UUID PRIMARY KEY)");
            statement.execute("CREATE TABLE chat_session (id UUID PRIMARY KEY)");
            statement.execute("INSERT INTO jchatmind_user VALUES (42)");
            statement.execute("INSERT INTO agent VALUES ('00000000-0000-0000-0000-000000000042')");
            statement.execute("INSERT INTO chat_session VALUES ('00000000-0000-0000-0000-000000000001')");
            String migration = Files.readString(Path.of("..", "sql", "skill",
                    "2026-10-22-create-builtin-skill-task.sql"), StandardCharsets.UTF_8);
            for (String sql : migration.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    private AnnotationConfigApplicationContext newApplicationContext(String schema) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "skill-task-postgres-l2",
                Map.of(
                        "spring.datasource.url", JDBC_URL + "?currentSchema=" + schema,
                        "spring.datasource.username", DB_USER,
                        "spring.datasource.password", "",
                        "mybatis.mapper-locations", "classpath*:mapper/*.xml",
                        "mybatis.type-handlers-package", "com.kama.jchatmind.typehandler"
                )
        ));
        context.register(SkillTaskPersistenceConfiguration.class);
        context.refresh();
        return context;
    }

    private BuiltinSkillExecutionService.PreparedSkillExecution prepared(Map<String, Object> input) {
        String question = (String) input.get("question");
        List<String> kbIds = (List<String>) input.get("kbIds");
        SkillDefinition definition = new SkillDefinition(
                "technical-decision-comparison", "v1", "技术方案对比", List.of("question", "kbIds"),
                List.of("KnowledgeTool"), SkillExecutionMode.SYNC, 30, 2, false
        );
        PreparedSkillInvocation invocation = new PreparedSkillInvocation(
                definition,
                Map.of("question", question, "kbIds", kbIds),
                kbIds
        );
        return new BuiltinSkillExecutionService.PreparedSkillExecution(
                "42", AGENT_ID, SESSION_ID, "technical-decision-comparison", invocation
        );
    }

    private void dropSchema(String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, DB_USER, "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void assertSchemaDoesNotExist(String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, DB_USER, "");
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '" + schema + "'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isZero();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @MapperScan("com.kama.jchatmind.mapper")
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            MybatisAutoConfiguration.class
    })
    static class SkillTaskPersistenceConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        BuiltinSkillExecutionService builtinSkillExecutionService() {
            return mock(BuiltinSkillExecutionService.class);
        }
    }
}
