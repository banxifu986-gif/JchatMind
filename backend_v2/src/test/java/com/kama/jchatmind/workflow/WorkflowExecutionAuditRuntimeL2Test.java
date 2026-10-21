package com.kama.jchatmind.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.controller.WorkflowController;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.response.GetChatSessionResponse;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.webhook.WebhookProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "g4.workflow.audit.l2", matches = "true")
class WorkflowExecutionAuditRuntimeL2Test {

    private static final String CONTAINER_NAME = "jchatmind-g4-audit-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String DATABASE_NAME = "g4_audit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final int HOST_PORT = findFreePort();
    private static boolean containerStarted;
    private static boolean databaseCreated;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startIsolatedPostgres() throws Exception {
        ProcessResult started = docker(
                "run", "--rm", "--detach", "--name", CONTAINER_NAME,
                "--label", "org.jchatmind.test=g4-workflow-audit-l2",
                "--env", "POSTGRES_DB=postgres",
                "--env", "POSTGRES_USER=postgres",
                "--env", "POSTGRES_HOST_AUTH_METHOD=trust",
                "--publish", "127.0.0.1:" + HOST_PORT + ":5432",
                "postgres:16"
        );
        assertThat(started.exitCode()).withFailMessage(started.output()).isZero();
        containerStarted = true;
        awaitPostgres();

        String adminUrl = jdbcUrl("postgres");
        try (var connection = java.sql.DriverManager.getConnection(adminUrl, "postgres", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + DATABASE_NAME);
            databaseCreated = true;
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl(DATABASE_NAME),
                "postgres",
                ""
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(new PathResource(Path.of(
                            "..", "sql", "workflow", "2026-10-21-create-workflow-execution-audit.sql"
                    )))
            );
        }
    }

    @AfterAll
    static void stopIsolatedPostgres() throws Exception {
        if (containerStarted) {
            try {
                if (databaseCreated) {
                    String adminUrl = jdbcUrl("postgres");
                    try (var connection = java.sql.DriverManager.getConnection(adminUrl, "postgres", "");
                         var statement = connection.createStatement()) {
                        statement.execute("DROP DATABASE " + DATABASE_NAME + " WITH (FORCE)");
                    }
                }
            } finally {
                ProcessResult stopped = docker("stop", CONTAINER_NAME);
                assertThat(stopped.exitCode()).withFailMessage(stopped.output()).isZero();
                containerStarted = false;
            }
        }
    }

    @Test
    void shouldPersistHashedAuditAndDeleteItAfterItsNinetyDayRetention() throws Exception {
        WorkflowExecutionAuditRecorder recorder = new WorkflowExecutionAuditRecorder(
                jdbcTemplate,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC)
        );
        WorkflowPlan plan = new WorkflowPlan(
                1,
                10,
                List.of(new WorkflowStep(
                        "step-1",
                        "KnowledgeTool",
                        "fact-1",
                        "private claim",
                        List.of(new WorkflowEvidence("chunk-1", "fact-1", "private evidence"))
                ))
        );
        WorkflowExecutionResult result = WorkflowExecutionResult.completed(List.of(
                new WorkflowStepExecution("step-1", WorkflowStepExecutionStatus.SUCCEEDED, "private tool output")
        ));

        recorder.record("trace-l2", 7L, "private user request", plan, result, 123L);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM workflow_execution_audit", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT outcome FROM workflow_execution_audit", String.class
        )).isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM expires_at - created_at)::BIGINT FROM workflow_execution_audit",
                Long.class
        )).isEqualTo(90L * 24 * 60 * 60);
        String metadata = jdbcTemplate.queryForObject(
                "SELECT step_metadata::text FROM workflow_execution_audit", String.class
        );
        assertThat(metadata)
                .contains("step-1", "KnowledgeTool", "SUCCEEDED")
                .doesNotContain("private claim", "private evidence", "private tool output", "private user request");
        String requestHash = jdbcTemplate.queryForObject(
                "SELECT request_sha256 FROM workflow_execution_audit", String.class
        );
        assertThat(requestHash).isEqualTo(sha256("private user request")).matches("[0-9a-f]{64}");

        assertThat(executeServiceWorkflow(recorder, plan, result)).isEqualTo("WORKFLOW");
        assertThat(executeServiceFallback(recorder, plan)).isEqualTo("SINGLE_AGENT_FALLBACK");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM workflow_execution_audit", Integer.class))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_execution_audit WHERE outcome = 'FALLBACK_SUCCEEDED'",
                Integer.class
        )).isEqualTo(1);
        String allMetadata = jdbcTemplate.queryForObject(
                "SELECT string_agg(step_metadata::text, ' ') FROM workflow_execution_audit", String.class
        );
        assertThat(allMetadata).contains("single-agent-fallback")
                .doesNotContain("private service request", "private fallback question", "private fallback response");

        jdbcTemplate.update("UPDATE workflow_execution_audit SET created_at = CURRENT_TIMESTAMP - INTERVAL '100 days', expires_at = CURRENT_TIMESTAMP - INTERVAL '10 days'");
        WorkflowExecutionAuditRetentionJob retentionJob = new WorkflowExecutionAuditRetentionJob(jdbcTemplate);
        assertThat(retentionJob.purgeExpiredBatch()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM workflow_execution_audit", Integer.class))
                .isZero();
    }

    private String executeServiceWorkflow(
            WorkflowExecutionAuditRecorder recorder,
            WorkflowPlan plan,
            WorkflowExecutionResult execution
    ) throws Exception {
        AgentMapper agentMapper = mock(AgentMapper.class);
        ChatSessionFacadeService sessionService = mock(ChatSessionFacadeService.class);
        AgentKnowledgeBaseBindingService bindingService = mock(AgentKnowledgeBaseBindingService.class);
        KnowledgeBaseAccessService accessService = mock(KnowledgeBaseAccessService.class);
        RagService ragService = mock(RagService.class);
        WorkflowPlanner planner = mock(WorkflowPlanner.class);
        WorkflowPlanExecutor executor = mock(WorkflowPlanExecutor.class);
        WorkflowSingleAgentFallbackAnswerService fallbackService = mock(WorkflowSingleAgentFallbackAnswerService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(agentMapper.selectById("agent-l2")).thenReturn(Agent.builder()
                .id("agent-l2").userId("7").model("mock-model").allowedTools("[\"KnowledgeTool\"]").build());
        when(sessionService.getChatSession("session-l2", "7")).thenReturn(GetChatSessionResponse.builder()
                .chatSession(ChatSessionVO.builder().id("session-l2").agentId("agent-l2").build()).build());
        when(bindingService.getBoundKnowledgeBaseIds("agent-l2")).thenReturn(List.of("kb-l2"));
        when(accessService.requireAccessibleKnowledgeBaseIds(List.of("kb-l2"), "7")).thenReturn(List.of("kb-l2"));
        RagRetrievalResult retrieved = new RagRetrievalResult();
        retrieved.setChunkId("chunk-l2");
        retrieved.setContent("private service evidence");
        when(ragService.retrieve(List.of("kb-l2"), "private service request", 10)).thenReturn(List.of(retrieved));
        when(planner.plan(eq("mock-model"), eq("private service request"), eq(List.of("KnowledgeTool")), any()))
                .thenReturn(new WorkflowPlanPlanningResult(true, plan, List.of()));
        when(executor.executeForWebhookSource(
                eq("7"), eq("agent-l2"), eq("session-l2"), eq("source-l2"), eq(plan), eq(List.of("KnowledgeTool")), any()
        )).thenReturn(execution);
        WebhookProperties properties = webhookProperties();

        WorkflowExecutionService service = new WorkflowExecutionService(
                agentMapper,
                sessionService,
                bindingService,
                accessService,
                ragService,
                planner,
                executor,
                recorder,
                fallbackService,
                properties,
                objectMapper
        );
        return executeThroughWorkflowController(service, "private service request");
    }

    private String executeServiceFallback(WorkflowExecutionAuditRecorder recorder, WorkflowPlan plan) throws Exception {
        AgentMapper agentMapper = mock(AgentMapper.class);
        ChatSessionFacadeService sessionService = mock(ChatSessionFacadeService.class);
        AgentKnowledgeBaseBindingService bindingService = mock(AgentKnowledgeBaseBindingService.class);
        KnowledgeBaseAccessService accessService = mock(KnowledgeBaseAccessService.class);
        RagService ragService = mock(RagService.class);
        WorkflowPlanner planner = mock(WorkflowPlanner.class);
        WorkflowPlanExecutor executor = mock(WorkflowPlanExecutor.class);
        WorkflowSingleAgentFallbackAnswerService fallbackService = mock(WorkflowSingleAgentFallbackAnswerService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(agentMapper.selectById("agent-l2")).thenReturn(Agent.builder()
                .id("agent-l2").userId("7").model("mock-model").allowedTools("[\"KnowledgeTool\"]").build());
        when(sessionService.getChatSession("session-l2", "7")).thenReturn(GetChatSessionResponse.builder()
                .chatSession(ChatSessionVO.builder().id("session-l2").agentId("agent-l2").build()).build());
        when(bindingService.getBoundKnowledgeBaseIds("agent-l2")).thenReturn(List.of("kb-l2"));
        when(accessService.requireAccessibleKnowledgeBaseIds(List.of("kb-l2"), "7")).thenReturn(List.of("kb-l2"));
        RagRetrievalResult retrieved = new RagRetrievalResult();
        retrieved.setChunkId("chunk-l2");
        retrieved.setContent("private service evidence");
        when(ragService.retrieve(List.of("kb-l2"), "private fallback question", 10)).thenReturn(List.of(retrieved));
        when(planner.plan(eq("mock-model"), eq("private fallback question"), eq(List.of("KnowledgeTool")), any()))
                .thenReturn(WorkflowPlanPlanningResult.rejected("fixed planning rejection"));
        when(fallbackService.answer("mock-model", "private fallback question", List.of(
                new WorkflowEvidence("chunk-l2", "chunk-l2", "private service evidence")
        ))).thenReturn("private fallback response");

        WorkflowExecutionService service = new WorkflowExecutionService(
                agentMapper,
                sessionService,
                bindingService,
                accessService,
                ragService,
                planner,
                executor,
                recorder,
                fallbackService,
                webhookProperties(),
                objectMapper
        );
        return executeThroughWorkflowController(service, "private fallback question");
    }

    private String executeThroughWorkflowController(WorkflowExecutionService service, String request) throws Exception {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new WorkflowController(requestScopeData, service)
        ).build();
        String body = new ObjectMapper().writeValueAsString(new WorkflowController.ExecuteWorkflowRequest(
                "agent-l2", "session-l2", request, "source-l2"
        ));
        var response = mockMvc.perform(post("/api/workflows/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return new ObjectMapper().readTree(response.getContentAsString())
                .path("data")
                .path("mode")
                .asText();
    }

    private WebhookProperties webhookProperties() {
        WebhookProperties properties = new WebhookProperties();
        WebhookProperties.Source source = new WebhookProperties.Source();
        source.setOwnerId(7L);
        source.setAllowedKbIds(java.util.Set.of("kb-l2"));
        source.setInboundSigningKey("isolated-inbound-key");
        source.setCallbackUrl("https://callback.example.invalid");
        source.setOutboundSigningKey("isolated-outbound-key");
        properties.getSources().put("source-l2", source);
        return properties;
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://127.0.0.1:" + HOST_PORT + "/" + database;
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception exception) {
            throw new IllegalStateException("无法分配隔离数据库端口", exception);
        }
    }

    private static void awaitPostgres() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (var connection = java.sql.DriverManager.getConnection(jdbcUrl("postgres"), "postgres", "")) {
                return;
            } catch (Exception ignored) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("隔离 PostgreSQL 未就绪");
    }

    private static ProcessResult docker(String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("docker");
        command.addAll(List.of(arguments));
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = processBuilder.start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("隔离 Docker 命令超时");
        }
        return new ProcessResult(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
