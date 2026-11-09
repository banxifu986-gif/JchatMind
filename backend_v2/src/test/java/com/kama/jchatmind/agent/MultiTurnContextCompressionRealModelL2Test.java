package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(MultiTurnContextCompressionRealModelL2Test.Config.class)
@EnabledIfEnvironmentVariable(named = "MULTI_TURN_CONTEXT_EVAL", matches = "true")
class MultiTurnContextCompressionRealModelL2Test {

    private static final long OWNER_ID = 72001L;
    private static final String AGENT_ID = "00000000-0000-0000-0000-000000072101";
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000072102";
    private static final String KB_ID = "00000000-0000-0000-0000-000000072111";
    private static final String TASK_MARKER = "TASK-77F3";
    private static final String DATABASE_NAME = "g1_model_scope_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String CONTAINER_NAME = "jchatmind-multi-turn-" + UUID.randomUUID().toString().substring(0, 8);
    private static final int HOST_PORT = findFreePort();
    private static boolean containerStarted;
    private static final List<Map<String, Object>> TURN_REPORTS = new ArrayList<>();
    private static final List<String> FAILURES = new ArrayList<>();
    private static Instant startedAt;
    private static Instant finishedAt;

    @Autowired
    private JChatMindFactory factory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MultiTurnRecordingRagService ragService;

    @Autowired
    private DecoyToolRegistry decoyToolRegistry;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        startedAt = Instant.now();
        System.setProperty("g1.pg.url", jdbcUrl(DATABASE_NAME));
        System.setProperty("g1.pg.nonce", DATABASE_NAME.substring("g1_model_scope_".length()));
        startIsolatedPostgres();
        registry.add("spring.config.import", () -> "optional:file:.env[.properties]");
        registry.add("spring.ai.deepseek.api-key", () -> "${DEEPSEEK_API_KEY}");
        registry.add("spring.ai.deepseek.base-url", () -> "${DEEPSEEK_BASE_URL:https://api.deepseek.com}");
        registry.add("spring.ai.deepseek.chat.options.model", () -> "${DEEPSEEK_MODEL:deepseek-chat}");
        registry.add("spring.ai.zhipuai.chat.enabled", () -> "false");
        registry.add("spring.ai.mcp.client.enabled", () -> "false");
        registry.add("spring.ai.mcp.server.enabled", () -> "false");
        registry.add("spring.main.web-application-type", () -> "none");
        registry.add("g1.pg.url", () -> jdbcUrl(DATABASE_NAME));
        registry.add("g1.pg.nonce", () -> DATABASE_NAME.substring("g1_model_scope_".length()));
    }

    @AfterAll
    static void stopContainer() {
        finishedAt = Instant.now();
        writeReport();
        if (containerStarted) {
            docker("stop", CONTAINER_NAME);
            containerStarted = false;
        }
    }

    @BeforeEach
    void setUp() {
        ragService.clear();
        decoyToolRegistry.clear();
        TURN_REPORTS.clear();
        recreateSchema();
        seedSession();
    }

    @Test
    void realDeepSeekKeepsTaskGoalAfterCompressionAndSelectsOnlyKnowledgeTool() throws Exception {
        try {
            assertContinuationTurn();
        assertFollowUpTurn();
            assertTopicSwitchTurn();
            assertGenericTurn("monitoring-follow-up", "请继续查询知识库，补充发布后监控指标。");
            assertGenericTurn("file-doc-switch", "暂停之前任务，只说明项目文档中的文件上传规范是什么？");
            assertGenericTurn("rollback-return", "继续之前的任务，请查询知识库并整理回滚要求。");
        } catch (Throwable throwable) {
            FAILURES.add(throwable.getMessage());
            throw throwable;
        }
    }

    private void assertContinuationTurn() throws Exception {
        int before = messageCount();
        String input = "继续之前的任务，请查询知识库并整理最终发布清单。";
        TurnResult result = runTurn(input, before);

        String summary = conversationSummary(result.runtime());
        recordTurn("continuation", input, result, summary);
        assertThat(summary).isNotBlank();
        assertThat(summary).hasSizeLessThanOrEqualTo(4000);
        assertThat(summary).contains(TASK_MARKER);

        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.toolCalls()).allSatisfy(call -> assertThat(call.path("name").asText()).isEqualTo("KnowledgeTool"));
        assertThat(ragService.calls()).isNotEmpty();
        assertThat(ragService.queries())
                .anySatisfy(query -> assertThat(query).containsAnyOf("发布", "上线"));
        assertAuthorizedRagCalls();
        assertPersistedTurn(result.messages());
        assertNoDecoyToolCall();
    }

    private void assertFollowUpTurn() throws Exception {
        int before = messageCount();
        String input = "请继续查询知识库，补充灰度发布注意事项。";
        TurnResult result = runTurn(input, before);

        recordTurn("follow-up", input, result, conversationSummary(result.runtime()));
        assertThat(result.toolCalls()).allSatisfy(call -> assertThat(call.path("name").asText()).isEqualTo("KnowledgeTool"));
        if (!result.toolCalls().isEmpty()) {
            assertPersistedTurn(result.messages());
        }
        assertNoDecoyToolCall();
    }

    private void assertTopicSwitchTurn() throws Exception {
        int before = messageCount();
        String input = "暂停之前任务，只说明项目文档中的邮件发送规范是什么？";
        TurnResult result = runTurn(input, before);

        recordTurn("topic-switch", input, result, conversationSummary(result.runtime()));
        assertThat(result.toolCalls()).allSatisfy(call -> assertThat(call.path("name").asText()).isEqualTo("KnowledgeTool"));
        if (!ragService.calls().isEmpty()) {
            assertThat(ragService.queries()).noneSatisfy(query -> assertThat(query).contains(TASK_MARKER));
            assertAuthorizedRagCalls();
        }
        if (!result.toolCalls().isEmpty()) {
            assertPersistedTurn(result.messages());
        }
        assertNoDecoyToolCall();
    }

    private void assertGenericTurn(String name, String input) {
        int before = messageCount();
        TurnResult result = runTurn(input, before);
        String summary = conversationSummary(result.runtime());
        recordTurn(name, input, result, summary);
        assertThat(summary).hasSizeLessThanOrEqualTo(4000);
        assertThat(result.toolCalls()).allSatisfy(call -> assertThat(call.path("name").asText()).isEqualTo("KnowledgeTool"));
        if (!ragService.calls().isEmpty()) {
            assertAuthorizedRagCalls();
        }
        if (!result.toolCalls().isEmpty()) {
            assertPersistedTurn(result.messages());
        }
        assertNoDecoyToolCall();
    }

    private TurnResult runTurn(String input, int before) {
        ragService.clear();
        decoyToolRegistry.clear();
        jdbcTemplate.update(
                "INSERT INTO chat_message (session_id, role, content, metadata) VALUES (?::uuid, 'user', ?, NULL)",
                SESSION_ID, input
        );
        JChatMind runtime = factory.create(String.valueOf(OWNER_ID), AGENT_ID, SESSION_ID);
        runtime.run();

        int after = messageCount();
        List<Map<String, Object>> messages = jdbcTemplate.queryForList(
                "SELECT role, content, metadata::text AS metadata FROM chat_message WHERE session_id = ?::uuid ORDER BY created_at, id",
                SESSION_ID
        ).subList(before, after);
        List<JsonNode> toolCalls = messages.stream()
                .filter(message -> String.valueOf(message.get("role")).equalsIgnoreCase("assistant"))
                .map(message -> readMetadata(String.valueOf(message.get("metadata"))))
                .filter(metadata -> metadata.has("toolCalls"))
                .flatMap(metadata -> toList(metadata.path("toolCalls")).stream())
                .toList();
        return new TurnResult(runtime, messages, toolCalls);
    }

    private int messageCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id = ?::uuid",
                Integer.class,
                SESSION_ID
        );
        return count == null ? 0 : count;
    }

    private void assertAuthorizedRagCalls() {
        assertThat(ragService.calls()).isNotEmpty();
        assertThat(ragService.calls()).allSatisfy(call -> assertThat(call.kbIds()).containsExactly(KB_ID));
    }

    private void assertPersistedTurn(List<Map<String, Object>> messages) {
        List<String> roles = messages.stream().map(message -> String.valueOf(message.get("role")).toLowerCase()).toList();
        assertThat(roles).containsSubsequence("user", "assistant", "tool", "assistant");
        String finalAssistant = messages.stream()
                .filter(message -> String.valueOf(message.get("role")).equalsIgnoreCase("assistant"))
                .map(message -> String.valueOf(message.get("content")))
                .reduce((first, second) -> second)
                .orElse("");
        assertThat(finalAssistant).isNotBlank();
    }

    private void assertNoDecoyToolCall() {
        assertThat(decoyToolRegistry.calls()).isZero();
    }

    private void recordTurn(String name, String input, TurnResult result, String summary) {
        Map<String, Object> report = Map.of(
                "name", name,
                "input", input,
                "summaryLength", summary == null ? 0 : summary.length(),
                "summaryContainsTaskMarker", summary != null && summary.contains(TASK_MARKER),
                "toolCalls", result.toolCalls().stream().map(call -> call.path("name").asText()).toList(),
                "ragQueries", List.copyOf(ragService.queries()),
                "ragKbIds", ragService.calls().stream().map(call -> List.copyOf(call.kbIds())).toList()
        );
        TURN_REPORTS.add(report);
    }

    private String conversationSummary(JChatMind runtime) {
        Object summary = ReflectionTestUtils.getField(runtime, "conversationSummary");
        return summary == null ? "" : String.valueOf(summary);
    }

    private JsonNode readMetadata(String metadata) {
        if (!StringUtils.hasText(metadata)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(metadata);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析消息 metadata", e);
        }
    }

    private List<JsonNode> toList(JsonNode array) {
        List<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        return values;
    }

    private static void writeReport() {
        try {
            Path reportPath = Path.of("target", "agent-eval", "multi-turn-context-v1.json");
            Files.createDirectories(reportPath.getParent());
            Map<String, Object> report = Map.of(
                    "model", resolveModelName(),
                    "startedAt", startedAt == null ? "" : startedAt.toString(),
                    "finishedAt", finishedAt == null ? "" : finishedAt.toString(),
                    "status", FAILURES.isEmpty() ? "PASSED" : "FAILED",
                    "failures", List.copyOf(FAILURES),
                    "turns", List.copyOf(TURN_REPORTS)
            );
            Files.writeString(reportPath, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } catch (IOException e) {
            throw new IllegalStateException("无法写入评测报告", e);
        }
    }

    private static void startIsolatedPostgres() {
        ProcessResult started = docker(
                "run", "--rm", "--detach", "--name", CONTAINER_NAME,
                "--label", "org.jchatmind.test=multi-turn-context-l2",
                "--env", "POSTGRES_DB=" + DATABASE_NAME,
                "--env", "POSTGRES_USER=g1scope",
                "--env", "POSTGRES_HOST_AUTH_METHOD=trust",
                "--publish", "127.0.0.1:" + HOST_PORT + ":5432",
                "postgres:16"
        );
        assertThat(started.exitCode()).withFailMessage(started.output()).isZero();
        containerStarted = true;
        awaitPostgres();
    }

    private static void awaitPostgres() {
        for (int attempt = 0; attempt < 60; attempt++) {
            try (var connection = java.sql.DriverManager.getConnection(jdbcUrl(DATABASE_NAME), "g1scope", "")) {
                if (connection.isValid(1)) {
                    return;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 PostgreSQL 被中断", e);
            }
        }
        throw new IllegalStateException("等待 PostgreSQL 启动超时");
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://127.0.0.1:" + HOST_PORT + "/" + database;
    }

    private static int findFreePort() {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("无法获取可用端口", e);
        }
    }

    private static ProcessResult docker(String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (var inputStream = process.getInputStream()) {
                output = new String(inputStream.readAllBytes());
            }
            int exitCode = process.waitFor();
            return new ProcessResult(exitCode, output);
        } catch (Exception e) {
            throw new IllegalStateException("执行 docker 命令失败", e);
        }
    }

    private static String resolveModelName() {
        String model = System.getenv("DEEPSEEK_MODEL");
        return StringUtils.hasText(model) ? model : "deepseek-flash";
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少运行时环境变量 " + name);
        }
        return value;
    }

    private void recreateSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS chat_message, chat_session, agent_knowledge_base, user_memory, user_memory_candidate, agent, knowledge_base, jchatmind_user CASCADE");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbcTemplate.execute("CREATE TABLE jchatmind_user (user_id BIGINT PRIMARY KEY, account VARCHAR(128), username VARCHAR(128), password VARCHAR(128))");
        jdbcTemplate.execute("CREATE TABLE knowledge_base (id UUID PRIMARY KEY, name VARCHAR(128) NOT NULL, description VARCHAR(255), metadata JSONB, owner_id BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW())");
        jdbcTemplate.execute("CREATE TABLE agent (id UUID PRIMARY KEY, user_id BIGINT NOT NULL, name VARCHAR(128) NOT NULL, description VARCHAR(255), system_prompt TEXT, model VARCHAR(128) NOT NULL, allowed_tools JSONB NOT NULL, chat_options JSONB NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW())");
        jdbcTemplate.execute("CREATE TABLE agent_knowledge_base (agent_id UUID NOT NULL, kb_id UUID NOT NULL, bound_by_user_id BIGINT NOT NULL, bound_at TIMESTAMP NOT NULL DEFAULT NOW(), PRIMARY KEY (agent_id, kb_id))");
        jdbcTemplate.execute("CREATE TABLE chat_session (id UUID PRIMARY KEY, user_id VARCHAR(64) NOT NULL, agent_id UUID NOT NULL, title VARCHAR(255), metadata JSONB, created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW())");
        jdbcTemplate.execute("CREATE TABLE chat_message (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), session_id UUID NOT NULL, role VARCHAR(32) NOT NULL, content TEXT, metadata JSONB, created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW())");
        jdbcTemplate.execute("CREATE TABLE agent_execution_turn_message (chat_message_id UUID PRIMARY KEY, session_id UUID NOT NULL, sequence_no BIGINT NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE user_memory (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id VARCHAR(64) NOT NULL, session_id UUID, memory_type VARCHAR(32), content TEXT, importance VARCHAR(16), evidence_message_id UUID, evidence_text TEXT, embedding TEXT, superseded_by_memory_id UUID, expires_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW())");
        jdbcTemplate.execute("CREATE TABLE user_memory_candidate (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id VARCHAR(64) NOT NULL, session_id UUID, memory_type VARCHAR(32), content TEXT, evidence TEXT, importance VARCHAR(16), evidence_message_id UUID, status VARCHAR(32), created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW())");
    }

    private void seedSession() {
        jdbcTemplate.update("INSERT INTO jchatmind_user (user_id, account, username, password) VALUES (?, ?, ?, ?)",
                OWNER_ID, "multi_turn_owner", "multi-turn-owner", "isolated");
        jdbcTemplate.update(
                "INSERT INTO knowledge_base (id, name, description, metadata, owner_id) VALUES (?::uuid, ?, ?, '{}'::jsonb, ?)",
                KB_ID, "发布规范知识库", "多轮上下文评测知识库", OWNER_ID
        );
        jdbcTemplate.update(
                "INSERT INTO agent (id, user_id, name, description, system_prompt, model, allowed_tools, chat_options) VALUES (?::uuid, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)",
                AGENT_ID,
                OWNER_ID,
                "多轮上下文评测 Agent",
                "验证长上下文压缩、任务目标和工具选择",
                """
                        你负责帮助用户延续当前任务。回答必须基于 KnowledgeTool 返回的证据，不要编造。 \
                        如果用户要求查询知识库，必须调用 KnowledgeTool，并且每轮最多调用一次；不要调用 sendEmail、readFile 或其他未明确要求的工具。 \
                        如果用户显式切换话题，优先响应最新请求。
                        """,
                "deepseek-chat",
                "[\"KnowledgeTool\",\"sendEmail\",\"readFile\"]",
                "{\"messageLength\":20}"
        );
        jdbcTemplate.update(
                "INSERT INTO agent_knowledge_base (agent_id, kb_id, bound_by_user_id) VALUES (?::uuid, ?::uuid, ?)",
                AGENT_ID, KB_ID, OWNER_ID
        );
        jdbcTemplate.update(
                "INSERT INTO chat_session (id, user_id, agent_id, title, metadata) VALUES (?::uuid, ?, ?::uuid, ?, '{}'::jsonb)",
                SESSION_ID, String.valueOf(OWNER_ID), AGENT_ID, "多轮上下文压缩评测"
        );
        insertLongContext();
    }

    private void insertLongContext() {
        for (int index = 0; index < 12; index++) {
            String role = index % 2 == 0 ? "user" : "assistant";
            String content = index == 0
                    ? TASK_MARKER + " 的最终交付物是发布清单。请记住这个目标。"
                            + "任务上下文包含发布流程、责任人、灰度节奏和回滚要求。".repeat(25)
                    : ("与当前任务无关的背景信息，用于验证压缩后不会污染目标。" + index + "。")
                            .repeat(25);
            jdbcTemplate.update(
                    "INSERT INTO chat_message (session_id, role, content, metadata) VALUES (?::uuid, ?, ?, ?::jsonb)",
                    SESSION_ID, role, content, role.equals("assistant") ? "{\"toolCalls\":[]}" : null
            );
        }
    }

    @TestConfiguration
    static class Config extends G1ModelDrivenSessionScopeRuntimeL2TestConfig {



        @Override
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        public MultiTurnRecordingRagService recordingRagService() {
            return new MultiTurnRecordingRagService();
        }

        @Override
        @org.springframework.context.annotation.Bean("externalToolCallbackProvider")
        public ToolCallbackProvider externalToolCallbackProvider() {
            DecoyToolCallback sendEmail = new DecoyToolCallback("sendEmail", decoyToolRegistry());
            DecoyToolCallback readFile = new DecoyToolCallback("readFile", decoyToolRegistry());
            return () -> new ToolCallback[]{sendEmail, readFile};
        }

        @org.springframework.context.annotation.Bean
        public DecoyToolRegistry decoyToolRegistry() {
            return new DecoyToolRegistry();
        }
    }

    public static class MultiTurnRecordingRagService extends G1ModelDrivenSessionScopeRuntimeL2Test.RecordingRagService {

        private final List<RagCall> calls = new CopyOnWriteArrayList<>();

        @Override
        public float[] embed(String text) {
            return new float[0];
        }

        @Override
        public List<String> similaritySearch(List<String> kbIds, String title) {
            return List.of();
        }

        @Override
        public List<RagRetrievalResult> retrieve(List<String> kbIds, String query, int limit) {
            return retrieve(kbIds, query, null, limit);
        }

        @Override
        public List<RagRetrievalResult> retrieve(
                List<String> kbIds,
                String query,
                RagRetrievalContext context,
                int limit
        ) {
            calls.add(new RagCall(List.copyOf(kbIds), query));
            RagRetrievalResult result = new RagRetrievalResult();
            result.setChunkId("multi-turn-chunk");
            result.setKbId(KB_ID);
            result.setDocId("multi-turn-doc");
            result.setContent("发布清单证据：" + TASK_MARKER + "。灰度发布需要先小流量观察，再逐步扩大范围。");
            result.setMetadata("{\"sourceType\":\"runtime-test\",\"sourceName\":\"multi-turn source\",\"contentPath\":\"multi-turn\"}");
            return List.of(result);
        }

        @Override
        public List<RagRetrievalResult> retrievePdfPageAssets(
                List<String> kbIds,
                String query,
                RagRetrievalContext context,
                int limit
        ) {
            return retrieve(kbIds, query, context, limit);
        }

        @Override
        public List<RagRetrievalResult> retrieveImageAssets(
                List<String> kbIds,
                String query,
                RagRetrievalContext context,
                int limit
        ) {
            return List.of();
        }

        @Override
        public List<RagRetrievalResult> retrieveMarkdownTableAssets(
                List<String> kbIds,
                String query,
                RagRetrievalContext context,
                int limit
        ) {
            return retrieve(kbIds, query, context, limit);
        }

        @Override
        public Map<String, Long> indexRevisions(List<String> kbIds) {
            return Map.of(KB_ID, 0L);
        }

        @Override
        public String retrievalConfigurationFingerprint() {
            return "multi-turn-context-test";
        }

        List<RagCall> calls() {
            return new ArrayList<>(calls);
        }

        List<String> queries() {
            return calls.stream().map(RagCall::query).toList();
        }

        void clear() {
            calls.clear();
        }
    }

    public record RagCall(List<String> kbIds, String query) {
    }

    public static class DecoyToolRegistry {

        private final AtomicInteger calls = new AtomicInteger();

        void record() {
            calls.incrementAndGet();
        }

        int calls() {
            return calls.get();
        }

        void clear() {
            calls.set(0);
        }
    }

    public static class DecoyToolCallback implements ToolCallback {

        private final String name;
        private final DecoyToolRegistry registry;

        private DecoyToolCallback(String name, DecoyToolRegistry registry) {
            this.name = name;
            this.registry = registry;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(name)
                    .description("测试专用 decoy 工具")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            registry.record();
            return "不应调用";
        }
    }

    private record TurnResult(
            JChatMind runtime,
            List<Map<String, Object>> messages,
            List<JsonNode> toolCalls
    ) {
    }

    private record ProcessResult(int exitCode, String output) {
    }
}



































