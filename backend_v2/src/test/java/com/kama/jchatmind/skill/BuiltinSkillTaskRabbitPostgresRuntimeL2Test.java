package com.kama.jchatmind.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.mapper.BuiltinSkillTaskMapper;
import com.kama.jchatmind.model.entity.BuiltinSkillExecutionTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "g3.skill.task.rabbit.l2", matches = "true")
class BuiltinSkillTaskRabbitPostgresRuntimeL2Test {

    private static final String JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval";
    private static final String DB_USER = "rag_eval";
    private static final String AGENT_ID = "00000000-0000-0000-0000-000000000042";
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void consumesDuplicateRabbitDeliveriesAndPersistsOneSuccessfulTaskAttempt() throws Exception {
        String rabbitHost = System.getenv("JCHATMIND_G3_RABBIT_L2_HOST");
        String rabbitPort = System.getenv("JCHATMIND_G3_RABBIT_L2_PORT");
        String rabbitUser = System.getenv("JCHATMIND_G3_RABBIT_L2_USER");
        String rabbitPassword = System.getenv("JCHATMIND_G3_RABBIT_L2_PASSWORD");
        assumeTrue(StringUtils.hasText(rabbitHost) && StringUtils.hasText(rabbitPort)
                && StringUtils.hasText(rabbitUser) && StringUtils.hasText(rabbitPassword));

        String schema = "g3_skill_rabbit_" + UUID.randomUUID().toString().replace("-", "");
        AnnotationConfigApplicationContext context = null;
        CachingConnectionFactory connectionFactory = null;
        RabbitAdmin rabbitAdmin = null;
        SimpleMessageListenerContainer listener = null;
        createSchema(schema);
        try {
            createPrerequisitesAndApplyTaskMigration(schema);
            context = newApplicationContext(schema);
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            BuiltinSkillTaskMapper mapper = context.getBean(BuiltinSkillTaskMapper.class);
            BuiltinSkillExecutionService executionService = context.getBean(BuiltinSkillExecutionService.class);
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            BuiltinSkillExecutionTask successTask = task(mapper, objectMapper, "并发 Rabbit 重复投递", 5);
            BuiltinSkillExecutionTask retryTask = task(mapper, objectMapper, "transient retry", 5);
            BuiltinSkillExecutionTask deadLetterTask = task(mapper, objectMapper, "exhausted failure", 1);
            AtomicInteger retryExecutionCount = new AtomicInteger();
            when(executionService.prepare(anyLong(), anyString(), anyString(), anyString(), anyMap()))
                    .thenAnswer(invocation -> {
                        Map<String, Object> requestInput = invocation.getArgument(4);
                        PreparedSkillInvocation preparedInvocation = new BuiltinSkillRegistry().prepare(
                                "technical-decision-comparison", requestInput, List.of("kb-1")
                        );
                        return new BuiltinSkillExecutionService.PreparedSkillExecution(
                                "42", AGENT_ID, SESSION_ID, "technical-decision-comparison", preparedInvocation
                        );
                    });
            when(executionService.execute(any(BuiltinSkillExecutionService.PreparedSkillExecution.class)))
                    .thenAnswer(invocation -> {
                        BuiltinSkillExecutionService.PreparedSkillExecution prepared = invocation.getArgument(0);
                        String question = (String) prepared.invocation().input().get("question");
                        if ("transient retry".equals(question) && retryExecutionCount.getAndIncrement() == 0) {
                            throw new IllegalStateException("transient details must not be persisted");
                        }
                        if ("exhausted failure".equals(question)) {
                            throw new IllegalStateException("dead-letter details must not be persisted");
                        }
                        return new BuiltinSkillExecutionService.ExecutionResponse(
                                Map.of("abstained", true, "reason", "no evidence"), true, List.of()
                        );
                    });

            connectionFactory = new CachingConnectionFactory(rabbitHost, Integer.parseInt(rabbitPort));
            connectionFactory.setUsername(rabbitUser);
            connectionFactory.setPassword(rabbitPassword);
            RabbitMQConfig rabbitConfig = new RabbitMQConfig();
            rabbitAdmin = new RabbitAdmin(connectionFactory);
            rabbitAdmin.declareExchange(rabbitConfig.skillTaskExchange());
            rabbitAdmin.declareExchange(rabbitConfig.skillTaskRetryExchange());
            rabbitAdmin.declareExchange(rabbitConfig.skillTaskDeadLetterExchange());
            rabbitAdmin.declareQueue(rabbitConfig.skillTaskQueue());
            rabbitAdmin.declareQueue(rabbitConfig.skillTaskRetryQueue());
            rabbitAdmin.declareQueue(rabbitConfig.skillTaskDeadLetterQueue());
            rabbitAdmin.declareBinding(rabbitConfig.skillTaskBinding());
            rabbitAdmin.declareBinding(rabbitConfig.skillTaskRetryBinding());
            rabbitAdmin.declareBinding(rabbitConfig.skillTaskDeadLetterBinding());

            BuiltinSkillTaskWorker worker = new BuiltinSkillTaskWorker(
                    mapper, executionService, rabbitConfig.rabbitTemplate(connectionFactory), objectMapper
            );
            CountDownLatch successDeliveriesProcessed = new CountDownLatch(2);
            CountDownLatch retryDeliveriesProcessed = new CountDownLatch(2);
            CountDownLatch deadLetterDeliveryProcessed = new CountDownLatch(1);
            listener = new SimpleMessageListenerContainer(connectionFactory);
            listener.setQueueNames(RabbitMQConfig.BUILTIN_SKILL_TASK_QUEUE);
            listener.setConcurrentConsumers(1);
            listener.setPrefetchCount(1);
            listener.setMessageListener((MessageListener) message -> {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                worker.onMessage(body);
                if (body.contains(successTask.getTaskId())) {
                    successDeliveriesProcessed.countDown();
                } else if (body.contains(retryTask.getTaskId())) {
                    retryDeliveriesProcessed.countDown();
                } else if (body.contains(deadLetterTask.getTaskId())) {
                    deadLetterDeliveryProcessed.countDown();
                }
            });
            listener.start();

            BuiltinSkillTaskPublisher publisher = new BuiltinSkillTaskPublisher(
                    rabbitConfig.rabbitTemplate(connectionFactory)
            );
            publisher.publish(successTask.getTaskId());
            publisher.publish(successTask.getTaskId());

            assertThat(successDeliveriesProcessed.await(10, TimeUnit.SECONDS)).isTrue();
            BuiltinSkillExecutionTask completed = mapper.selectByTaskId(successTask.getTaskId());
            assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
            assertThat(completed.getAttemptCount()).isEqualTo(1);
            assertThat(completed.getProgress()).isEqualTo((short) 100);
            assertThat(completed.getResultJson()).contains("no evidence");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM builtin_skill_execution_task WHERE task_id = ?::uuid",
                    Integer.class,
                    successTask.getTaskId()
            )).isEqualTo(1);

            publisher.publish(retryTask.getTaskId());
            assertThat(retryDeliveriesProcessed.await(45, TimeUnit.SECONDS)).isTrue();
            BuiltinSkillExecutionTask retried = mapper.selectByTaskId(retryTask.getTaskId());
            assertThat(retried.getStatus()).isEqualTo("SUCCEEDED");
            assertThat(retried.getAttemptCount()).isEqualTo(2);

            publisher.publish(deadLetterTask.getTaskId());
            assertThat(deadLetterDeliveryProcessed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(mapper.selectByTaskId(deadLetterTask.getTaskId()).getStatus()).isEqualTo("DEAD_LETTER");
            RabbitTemplate taskRabbitTemplate = rabbitConfig.rabbitTemplate(connectionFactory);
            Object deadLetterMessage = taskRabbitTemplate.receiveAndConvert(
                    RabbitMQConfig.BUILTIN_SKILL_TASK_DLQ, 5_000
            );
            assertThat(deadLetterMessage).isEqualTo(deadLetterTask.getTaskId());
        } finally {
            if (listener != null) {
                listener.stop();
            }
            if (rabbitAdmin != null) {
                rabbitAdmin.deleteQueue(RabbitMQConfig.BUILTIN_SKILL_TASK_QUEUE);
                rabbitAdmin.deleteQueue(RabbitMQConfig.BUILTIN_SKILL_TASK_RETRY_QUEUE);
                rabbitAdmin.deleteQueue(RabbitMQConfig.BUILTIN_SKILL_TASK_DLQ);
                rabbitAdmin.deleteExchange(RabbitMQConfig.BUILTIN_SKILL_TASK_EXCHANGE);
                rabbitAdmin.deleteExchange(RabbitMQConfig.BUILTIN_SKILL_TASK_RETRY_EXCHANGE);
                rabbitAdmin.deleteExchange(RabbitMQConfig.BUILTIN_SKILL_TASK_DLX);
            }
            if (connectionFactory != null) {
                connectionFactory.destroy();
            }
            if (context != null) {
                context.close();
            }
            dropSchema(schema);
        }
        assertSchemaDoesNotExist(schema);
    }

    private BuiltinSkillExecutionTask task(
            BuiltinSkillTaskMapper mapper,
            ObjectMapper objectMapper,
            String question,
            int maxAttempts
    ) throws Exception {
        BuiltinSkillExecutionTask task = new BuiltinSkillExecutionTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setRequesterUserId(42L);
        task.setTaskType("BUILTIN_SKILL");
        task.setAgentId(AGENT_ID);
        task.setSessionId(SESSION_ID);
        task.setSkillId("technical-decision-comparison");
        task.setSkillVersion("v1");
        task.setIdempotencyKey("rabbit-runtime-" + UUID.randomUUID());
        task.setRequestSha256("a".repeat(64));
        task.setInputSnapshot(objectMapper.writeValueAsString(
                Map.of("question", question, "kbIds", List.of("kb-1"))
        ));
        task.setStatus("PENDING");
        task.setProgress((short) 0);
        task.setAttemptCount(0);
        task.setMaxAttempts(maxAttempts);
        task.setDispatchAttemptCount(0);
        task.setResultReference("/api/skill-tasks/" + task.getTaskId());
        assertThat(mapper.insertIfAbsent(task)).isEqualTo(1);
        return task;
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
                "skill-rabbit-postgres-l2",
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
