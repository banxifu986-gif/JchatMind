package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(G1ModelDrivenSessionScopeRuntimeL2TestConfig.class)
@EnabledIfEnvironmentVariable(named = "G1_MODEL_SESSION_SCOPE_L2", matches = "true")
class G1ModelDrivenSessionScopeRuntimeL2Test {

    private static final long OWNER_ID = 71001L;
    private static final String AGENT_ID = "00000000-0000-0000-0000-000000007101";
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000007102";
    static final String KB_A1 = "00000000-0000-0000-0000-000000007111";
    private static final String KB_A2 = "00000000-0000-0000-0000-000000007112";
    static final String A1_EVIDENCE_MARKER = "A1_SCOPE_EVIDENCE_9F35";
    private static final String A2_EVIDENCE_MARKER = "A2_FORBIDDEN_EVIDENCE_6C42";

    @Autowired
    private JChatMindFactory factory;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecordingRagService recordingRagService;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.deepseek.api-key", () -> requiredEnvironment("G1_DS_API_KEY"));
        registry.add("spring.ai.deepseek.base-url", () -> requiredEnvironment("G1_DS_BASE_URL"));
        registry.add("spring.ai.deepseek.chat.options.model", () -> requiredEnvironment("G1_DS_MODEL"));
        registry.add("spring.ai.zhipuai.chat.enabled", () -> false);
        registry.add("spring.ai.mcp.client.enabled", () -> false);
        registry.add("spring.ai.mcp.server.enabled", () -> false);
        registry.add("spring.main.web-application-type", () -> "none");
    }

    @BeforeEach
    void setUp() {
        recordingRagService.clear();
        recreateSchema();
        seedSessionScope();
    }

    @Test
    void realDeepSeekToolCallKeepsSessionTemporaryKnowledgeBaseScopeAndPersistsOnlyScopedEvidence() throws Exception {
        JChatMind runtime = factory.create(String.valueOf(OWNER_ID), AGENT_ID, SESSION_ID);

        runtime.run();

        assertThat(recordingRagService.effectiveKbIdCalls())
                .isNotEmpty()
                .allSatisfy(kbIds -> assertThat(kbIds).containsExactly(KB_A1));

        List<Map<String, Object>> messages = jdbcTemplate.queryForList(
                "SELECT role, content, metadata::text AS metadata FROM chat_message ORDER BY created_at, id"
        );
        assertThat(messages.stream().map(row -> String.valueOf(row.get("role")).toLowerCase()).toList())
                .containsSubsequence("user", "assistant", "tool", "assistant");

        Map<String, Object> toolCallMessage = messages.stream()
                .filter(row -> String.valueOf(row.get("role")).equalsIgnoreCase("assistant"))
                .filter(row -> row.get("metadata") != null && String.valueOf(row.get("metadata")).contains("toolCalls"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("真实 DS 没有持久化 KnowledgeTool 调用消息"));
        JsonNode toolCalls = objectMapper.readTree(String.valueOf(toolCallMessage.get("metadata"))).path("toolCalls");
        assertThat(toolCalls.isArray()).isTrue();
        assertThat(toolCalls).hasSize(1);
        JsonNode toolCall = toolCalls.get(0);
        assertThat(toolCall.path("name").asText()).isEqualTo("KnowledgeTool");
        JsonNode arguments = objectMapper.readTree(toolCall.path("arguments").asText());
        assertThat(arguments.has("query")).isTrue();
        assertThat(arguments.has("kbIds")).isFalse();

        List<String> toolResponses = messages.stream()
                .filter(row -> String.valueOf(row.get("role")).equalsIgnoreCase("tool"))
                .map(row -> String.valueOf(row.get("content")))
                .toList();
        assertThat(toolResponses).isNotEmpty();
        assertThat(toolResponses).allSatisfy(content -> {
            assertThat(content).contains(A1_EVIDENCE_MARKER);
            assertThat(content).doesNotContain(A2_EVIDENCE_MARKER);
        });

        int lastToolMessageIndex = IntStream.range(0, messages.size())
                .filter(index -> String.valueOf(messages.get(index).get("role")).equalsIgnoreCase("tool"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("真实 DS 没有持久化 KnowledgeTool 响应消息"));
        String finalAssistantContent = IntStream.range(lastToolMessageIndex + 1, messages.size())
                .mapToObj(messages::get)
                .filter(row -> String.valueOf(row.get("role")).equalsIgnoreCase("assistant"))
                .reduce((first, second) -> second)
                .map(row -> String.valueOf(row.get("content")))
                .orElseThrow(() -> new AssertionError("真实 DS 没有持久化工具调用后的最终 Assistant 消息"));
        assertThat(finalAssistantContent).contains(A1_EVIDENCE_MARKER);
        assertThat(finalAssistantContent).doesNotContain(A2_EVIDENCE_MARKER);
        assertThat(messages.stream().map(row -> String.valueOf(row.get("content"))).toList())
                .noneMatch(content -> content.contains(A2_EVIDENCE_MARKER));
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
        jdbcTemplate.execute("CREATE TABLE user_memory (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id VARCHAR(64) NOT NULL, session_id UUID, memory_type VARCHAR(32), content TEXT, importance VARCHAR(16), evidence_message_id UUID, evidence_text TEXT, embedding TEXT, created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW())");
        jdbcTemplate.execute("CREATE TABLE user_memory_candidate (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id VARCHAR(64) NOT NULL, session_id UUID, memory_type VARCHAR(32), content TEXT, evidence TEXT, importance VARCHAR(16), evidence_message_id UUID, status VARCHAR(32), created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW())");
    }

    private void seedSessionScope() {
        jdbcTemplate.update("INSERT INTO jchatmind_user (user_id, account, username, password) VALUES (?, ?, ?, ?)",
                OWNER_ID, "g1_scope_owner", "scope-owner", "isolated");
        insertKnowledgeBase(KB_A1, "本会话知识库 A1");
        insertKnowledgeBase(KB_A2, "Agent 默认知识库 A2");
        jdbcTemplate.update(
                "INSERT INTO agent (id, user_id, name, description, system_prompt, model, allowed_tools, chat_options) VALUES (?::uuid, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)",
                AGENT_ID,
                OWNER_ID,
                "会话范围验证 Agent",
                "验证真实模型工具调用的知识库收窄",
                "本回合必须先调用一次 KnowledgeTool。工具参数 JSON 严格只能包含 query 字段，绝不能传 kbIds。收到工具结果后，最终答复必须逐字包含工具返回的证据标记，不能编造其他知识库的证据标记。",
                "deepseek-chat",
                "[]",
                "{\"messageLength\":10}"
        );
        jdbcTemplate.update(
                "INSERT INTO agent_knowledge_base (agent_id, kb_id, bound_by_user_id) VALUES (?::uuid, ?::uuid, ?), (?::uuid, ?::uuid, ?)",
                AGENT_ID, KB_A1, OWNER_ID, AGENT_ID, KB_A2, OWNER_ID
        );
        jdbcTemplate.update(
                "INSERT INTO chat_session (id, user_id, agent_id, title, metadata) VALUES (?::uuid, ?, ?::uuid, ?, ?::jsonb)",
                SESSION_ID,
                String.valueOf(OWNER_ID),
                AGENT_ID,
                "会话临时范围验证",
                "{\"retrievalContext\":{\"kbId\":\"" + KB_A1 + "\"}}"
        );
        jdbcTemplate.update(
                "INSERT INTO chat_message (session_id, role, content, metadata) VALUES (?::uuid, ?, ?, NULL)",
                SESSION_ID,
                "user",
                "请检索本会话选定知识库中的证据标记。必须先调用 KnowledgeTool，并且工具参数只传 query，不传 kbIds。最终答复请逐字返回工具结果里的证据标记。"
        );
    }

    private void insertKnowledgeBase(String knowledgeBaseId, String name) {
        jdbcTemplate.update(
                "INSERT INTO knowledge_base (id, name, description, metadata, owner_id) VALUES (?::uuid, ?, ?, '{}'::jsonb, ?)",
                knowledgeBaseId,
                name,
                "隔离范围 L2 fixture",
                OWNER_ID
        );
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少运行时环境变量 " + name);
        }
        return value;
    }

    public static class RecordingRagService implements RagService {

        private final List<List<String>> effectiveKbIdCalls = new CopyOnWriteArrayList<>();

        @Override
        public float[] embed(String text) {
            return new float[]{0.0f};
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
            effectiveKbIdCalls.add(List.copyOf(kbIds));
            if (!List.of(KB_A1).equals(kbIds)) {
                throw new AssertionError("真实工具调用未保持会话 A1 范围: " + kbIds);
            }
            RagRetrievalResult result = new RagRetrievalResult();
            result.setChunkId("00000000-0000-0000-0000-000000007121");
            result.setKbId(KB_A1);
            result.setDocId("00000000-0000-0000-0000-000000007122");
            result.setContent("仅 A1 可见的证据标记：" + A1_EVIDENCE_MARKER);
            result.setMetadata("{\"sourceType\":\"runtime-test\",\"sourceName\":\"A1 source\",\"contentPath\":\"session scope\"}");
            return List.of(result);
        }

        List<List<String>> effectiveKbIdCalls() {
            return new ArrayList<>(effectiveKbIdCalls);
        }

        void clear() {
            effectiveKbIdCalls.clear();
        }
    }
}
