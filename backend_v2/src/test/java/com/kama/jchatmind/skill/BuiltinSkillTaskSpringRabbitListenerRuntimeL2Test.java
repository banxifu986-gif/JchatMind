package com.kama.jchatmind.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.JwtUtil;
import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.BuiltinSkillTaskMapper;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.BuiltinSkillExecutionTask;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.response.GetChatSessionResponse;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "g3.skill.task.rabbit.spring.l2", matches = "true")
class BuiltinSkillTaskSpringRabbitListenerRuntimeL2Test {

    private static final String JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval";
    private static final String DB_USER = "rag_eval";
    private static final String AGENT_ID = "00000000-0000-0000-0000-000000000042";
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void assemblesTheProductionRabbitListenerAndProcessesAnOwnedTask() throws Exception {
        String rabbitHost = System.getenv("JCHATMIND_G3_RABBIT_L2_HOST");
        String rabbitPort = System.getenv("JCHATMIND_G3_RABBIT_L2_PORT");
        String rabbitUser = System.getenv("JCHATMIND_G3_RABBIT_L2_USER");
        String rabbitPassword = System.getenv("JCHATMIND_G3_RABBIT_L2_PASSWORD");
        assumeTrue(StringUtils.hasText(rabbitHost) && StringUtils.hasText(rabbitPort)
                && StringUtils.hasText(rabbitUser) && StringUtils.hasText(rabbitPassword));

        String schema = "g3_skill_listener_" + UUID.randomUUID().toString().replace("-", "");
        AnnotationConfigApplicationContext context = null;
        createSchema(schema);
        try {
            createPrerequisitesAndApplyTaskMigration(schema);
            context = newApplicationContext(schema, rabbitHost, rabbitPort, rabbitUser, rabbitPassword);
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            BuiltinSkillTaskService taskService = context.getBean(BuiltinSkillTaskService.class);
            BuiltinSkillTaskPublisher publisher = context.getBean(BuiltinSkillTaskPublisher.class);
            RabbitAdmin rabbitAdmin = context.getBean(RabbitAdmin.class);
            RabbitMQConfig rabbitConfig = context.getBean(RabbitMQConfig.class);
            rabbitAdmin.declareExchange(rabbitConfig.skillTaskExchange());
            rabbitAdmin.declareExchange(rabbitConfig.skillTaskRetryExchange());
            rabbitAdmin.declareExchange(rabbitConfig.skillTaskDeadLetterExchange());
            rabbitAdmin.declareQueue(rabbitConfig.skillTaskQueue());
            rabbitAdmin.declareQueue(rabbitConfig.skillTaskRetryQueue());
            rabbitAdmin.declareQueue(rabbitConfig.skillTaskDeadLetterQueue());
            rabbitAdmin.declareBinding(rabbitConfig.skillTaskBinding());
            rabbitAdmin.declareBinding(rabbitConfig.skillTaskRetryBinding());
            rabbitAdmin.declareBinding(rabbitConfig.skillTaskDeadLetterBinding());

            RabbitListenerEndpointRegistry registry = context.getBean(RabbitListenerEndpointRegistry.class);
            registry.start();
            assertThat(registry.getListenerContainers()).hasSize(1);
            assertThat(registry.getListenerContainers().iterator().next().isRunning()).isTrue();

            BuiltinSkillTaskService.SubmissionResult submitted = taskService.submit(
                    42L,
                    AGENT_ID,
                    SESSION_ID,
                    "technical-decision-comparison",
                    "spring-listener-" + UUID.randomUUID(),
                    Map.of("question", "真实 Rabbit listener 旅程", "kbIds", List.of("kb-1"))
            );
            publisher.publish(submitted.taskId());

            BuiltinSkillExecutionTask completed = awaitStatus(jdbcTemplate, submitted.taskId(), "SUCCEEDED");
            assertThat(completed.getAttemptCount()).isEqualTo(1);
            assertThat(completed.getResultJson()).contains("chunk-listener", "kb-1");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM builtin_skill_execution_task WHERE task_id = ?::uuid",
                    Integer.class,
                    submitted.taskId()
            )).isEqualTo(1);
        } finally {
            if (context != null) {
                context.close();
            }
            dropSchema(schema);
        }
        assertSchemaDoesNotExist(schema);
    }

    private AnnotationConfigApplicationContext newApplicationContext(
            String schema,
            String rabbitHost,
            String rabbitPort,
            String rabbitUser,
            String rabbitPassword
    ) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "skill-rabbit-listener-postgres-l2",
                Map.ofEntries(
                        Map.entry("spring.datasource.url", JDBC_URL + "?currentSchema=" + schema),
                        Map.entry("spring.datasource.username", DB_USER),
                        Map.entry("spring.datasource.password", ""),
                        Map.entry("spring.rabbitmq.host", rabbitHost),
                        Map.entry("spring.rabbitmq.port", rabbitPort),
                        Map.entry("spring.rabbitmq.username", rabbitUser),
                        Map.entry("spring.rabbitmq.password", rabbitPassword),
                        Map.entry("spring.rabbitmq.listener.simple.auto-startup", "false"),
                        Map.entry("builtin-skill.task.dispatch.enabled", "true"),
                        Map.entry("mybatis.mapper-locations", "classpath*:mapper/*.xml"),
                        Map.entry("mybatis.type-handlers-package", "com.kama.jchatmind.typehandler")
                )
        ));
        context.register(WorkerRabbitListenerConfiguration.class);
        context.refresh();
        return context;
    }

    private BuiltinSkillExecutionTask awaitStatus(JdbcTemplate jdbcTemplate, String taskId, String status)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        BuiltinSkillExecutionTask task = null;
        while (System.nanoTime() < deadline) {
            task = jdbcTemplate.queryForObject(
                    "SELECT task_id::text AS task_id, requester_user_id, task_type, agent_id::text AS agent_id, "
                            + "session_id::text AS session_id, skill_id, skill_version, idempotency_key, request_sha256, "
                            + "input_snapshot::text AS input_snapshot, status, progress, attempt_count, max_attempts, "
                            + "dispatch_attempt_count, result_json::text AS result_json, result_reference, "
                            + "failure_type, error_summary FROM builtin_skill_execution_task "
                            + "WHERE task_id = ?::uuid",
                    (resultSet, row) -> {
                        BuiltinSkillExecutionTask value = new BuiltinSkillExecutionTask();
                        value.setTaskId(resultSet.getString("task_id"));
                        value.setRequesterUserId(resultSet.getLong("requester_user_id"));
                        value.setTaskType(resultSet.getString("task_type"));
                        value.setAgentId(resultSet.getString("agent_id"));
                        value.setSessionId(resultSet.getString("session_id"));
                        value.setSkillId(resultSet.getString("skill_id"));
                        value.setSkillVersion(resultSet.getString("skill_version"));
                        value.setIdempotencyKey(resultSet.getString("idempotency_key"));
                        value.setRequestSha256(resultSet.getString("request_sha256"));
                        value.setInputSnapshot(resultSet.getString("input_snapshot"));
                        value.setStatus(resultSet.getString("status"));
                        value.setProgress(resultSet.getShort("progress"));
                        value.setAttemptCount(resultSet.getInt("attempt_count"));
                        value.setMaxAttempts(resultSet.getInt("max_attempts"));
                        value.setDispatchAttemptCount(resultSet.getInt("dispatch_attempt_count"));
                        value.setResultJson(resultSet.getString("result_json"));
                        value.setResultReference(resultSet.getString("result_reference"));
                        value.setFailureType(resultSet.getString("failure_type"));
                        value.setErrorSummary(resultSet.getString("error_summary"));
                        return value;
                    },
                    taskId
            );
            if (status.equals(task.getStatus())) {
                return task;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Task did not reach " + status + "; last status="
                + (task == null ? "missing" : task.getStatus()));
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
            for (String migrationPath : List.of(
                    "2026-10-22-create-builtin-skill-task.sql",
                    "2026-10-22-add-builtin-skill-task-lease-index.sql"
            )) {
                String migration = Files.readString(Path.of("..", "sql", "skill", migrationPath),
                        StandardCharsets.UTF_8);
                for (String sql : migration.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        }
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
    @EnableRabbit
    @MapperScan("com.kama.jchatmind.mapper")
    @Import(RabbitMQConfig.class)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            MybatisAutoConfiguration.class,
            RabbitAutoConfiguration.class
    })
    static class WorkerRabbitListenerConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ChatSessionFacadeService chatSessionFacadeService() {
            ChatSessionFacadeService service = mock(ChatSessionFacadeService.class);
            when(service.getChatSession(SESSION_ID, "42")).thenReturn(GetChatSessionResponse.builder()
                    .chatSession(ChatSessionVO.builder().id(SESSION_ID).agentId(AGENT_ID).build())
                    .build());
            return service;
        }

        @Primary
        @Bean
        AgentMapper testAgentMapper() {
            AgentMapper mapper = mock(AgentMapper.class);
            when(mapper.selectById(AGENT_ID)).thenReturn(Agent.builder()
                    .id(AGENT_ID)
                    .userId("42")
                    .allowedTools("[\"KnowledgeTool\"]")
                    .build());
            return mapper;
        }

        @Bean
        AgentKnowledgeBaseBindingService agentKnowledgeBaseBindingService() {
            AgentKnowledgeBaseBindingService service = mock(AgentKnowledgeBaseBindingService.class);
            when(service.getBoundKnowledgeBaseIds(AGENT_ID)).thenReturn(List.of("kb-1"));
            return service;
        }

        @Bean
        KnowledgeBaseAccessService knowledgeBaseAccessService() {
            KnowledgeBaseAccessService service = mock(KnowledgeBaseAccessService.class);
            when(service.requireAccessibleKnowledgeBaseIds(List.of("kb-1"), "42"))
                    .thenReturn(List.of("kb-1"));
            return service;
        }

        @Bean
        BuiltinSkillRegistry builtinSkillRegistry() {
            return new BuiltinSkillRegistry();
        }

        @Bean
        SkillKnowledgeToolExecutor skillKnowledgeToolExecutor() {
            return (userId, sessionId, question, knowledgeBaseIds) -> {
                RagRetrievalResult result = new RagRetrievalResult();
                result.setChunkId("chunk-listener");
                result.setKbId(knowledgeBaseIds.get(0));
                return SkillKnowledgeToolResult.executed(List.of(result));
            };
        }

        @Bean
        BuiltinSkillExecutor builtinSkillExecutor(
                BuiltinSkillRegistry registry,
                SkillKnowledgeToolExecutor skillKnowledgeToolExecutor
        ) {
            return new BuiltinSkillExecutor(registry, skillKnowledgeToolExecutor);
        }

        @Bean
        BuiltinSkillExecutionService builtinSkillExecutionService(
                ChatSessionFacadeService sessions,
                AgentMapper agents,
                AgentKnowledgeBaseBindingService bindings,
                KnowledgeBaseAccessService access,
                BuiltinSkillExecutor executor,
                ObjectMapper objectMapper
        ) {
            return new BuiltinSkillExecutionService(sessions, agents, bindings, access, executor, objectMapper);
        }

        @Bean
        BuiltinSkillTaskService builtinSkillTaskService(
                BuiltinSkillExecutionService executionService,
                BuiltinSkillTaskMapper mapper,
                ObjectMapper objectMapper
        ) {
            return new BuiltinSkillTaskService(executionService, mapper, objectMapper);
        }

        @Bean
        @ConditionalOnProperty(prefix = "builtin-skill.task.dispatch", name = "enabled", havingValue = "true")
        BuiltinSkillTaskWorker builtinSkillTaskWorker(
                BuiltinSkillTaskMapper mapper,
                BuiltinSkillExecutionService executionService,
                org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate,
                ObjectMapper objectMapper
        ) {
            return new BuiltinSkillTaskWorker(mapper, executionService, rabbitTemplate, objectMapper);
        }

        @Bean
        BuiltinSkillTaskPublisher builtinSkillTaskPublisher(
                org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate
        ) {
            return new BuiltinSkillTaskPublisher(rabbitTemplate);
        }
    }
}
