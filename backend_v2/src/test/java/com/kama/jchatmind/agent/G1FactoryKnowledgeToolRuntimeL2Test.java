package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.ai.mcp.client.enabled=false"
)
@EnabledIfEnvironmentVariable(named = "G1_FACTORY_L2", matches = "true")
class G1FactoryKnowledgeToolRuntimeL2Test {

    private static final long OWNER_A = 10001L;
    private static final long OWNER_B = 10002L;
    private static final String KB_A1 = "00000000-0000-0000-0000-000000000101";
    private static final String KB_A2 = "00000000-0000-0000-0000-000000000102";
    private static final String KB_B = "00000000-0000-0000-0000-000000000201";
    private static final String AGENT_A = "00000000-0000-0000-0000-000000001001";
    private static final String AGENT_EMPTY = "00000000-0000-0000-0000-000000001002";
    private static final String SESSION_A = "00000000-0000-0000-0000-000000002001";

    @Autowired
    private JChatMindFactory factory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void factoryRuntimeKeepsOwnerBindingEmptyBindingAndSessionNarrowingInsideKnowledgeTool() throws Exception {
        seedRuntimeData();

        JChatMind ownerRuntime = factory.create(String.valueOf(OWNER_A), AGENT_A, SESSION_A);
        assertThat(runtimeKnowledgeBaseIds(ownerRuntime))
                .containsExactlyInAnyOrder(KB_A1, KB_A2)
                .doesNotContain(KB_B);

        KnowledgeTools ownerKnowledgeTool = factoryKnowledgeTool(ownerRuntime);
        assertThat(ownerKnowledgeTool.knowledgeQuery("scope probe", List.of(KB_B)))
                .contains("未找到可检索的知识库");
        assertThat(effectiveKbIds(ownerKnowledgeTool, List.of())).containsExactly(KB_A1);
        assertThat(effectiveKbIds(ownerKnowledgeTool, List.of(KB_A2, KB_B))).containsExactly(KB_A2);

        JChatMind emptyRuntime = factory.create(String.valueOf(OWNER_A), AGENT_EMPTY, SESSION_A);
        assertThat(runtimeKnowledgeBaseIds(emptyRuntime)).isEmpty();
        assertThat(factoryKnowledgeTool(emptyRuntime).knowledgeQuery("scope probe", List.of(KB_B)))
                .contains("未找到可检索的知识库");
    }

    private void seedRuntimeData() {
        jdbcTemplate.update("INSERT INTO jchatmind_user (user_id, account, username, password) VALUES (?, ?, ?, ?)",
                OWNER_A, "g1_factory_a", "factory-a", "isolated");
        jdbcTemplate.update("INSERT INTO jchatmind_user (user_id, account, username, password) VALUES (?, ?, ?, ?)",
                OWNER_B, "g1_factory_b", "factory-b", "isolated");
        insertKnowledgeBase(KB_A1, OWNER_A, "A-1");
        insertKnowledgeBase(KB_A2, OWNER_A, "A-2");
        insertKnowledgeBase(KB_B, OWNER_B, "B-private");
        insertAgent(AGENT_A);
        insertAgent(AGENT_EMPTY);
        jdbcTemplate.update(
                "INSERT INTO agent_knowledge_base (agent_id, kb_id, bound_by_user_id) VALUES (?::uuid, ?::uuid, ?), (?::uuid, ?::uuid, ?)",
                AGENT_A, KB_A1, OWNER_A, AGENT_A, KB_A2, OWNER_A
        );
        jdbcTemplate.update(
                "INSERT INTO chat_session (id, user_id, agent_id, title, metadata) VALUES (?::uuid, ?, ?::uuid, ?, ?::jsonb)",
                SESSION_A, OWNER_A, AGENT_A, "scope", "{\"retrievalContext\":{\"kbId\":\"" + KB_A1 + "\"}}"
        );
    }

    private void insertKnowledgeBase(String id, long ownerId, String name) {
        jdbcTemplate.update(
                "INSERT INTO knowledge_base (id, name, description, metadata, owner_id) VALUES (?::uuid, ?, ?, '{}'::jsonb, ?)",
                id, name, "isolated", ownerId
        );
    }

    private void insertAgent(String agentId) {
        jdbcTemplate.update(
                "INSERT INTO agent (id, user_id, name, model, allowed_tools, chat_options) VALUES (?::uuid, ?, ?, ?, '[]'::jsonb, ?::jsonb)",
                agentId, OWNER_A, "scope-agent", "deepseek-chat", "{\"messageLength\":1}"
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> runtimeKnowledgeBaseIds(JChatMind runtime) throws Exception {
        Field field = JChatMind.class.getDeclaredField("availableKbs");
        field.setAccessible(true);
        return ((List<KnowledgeBaseDTO>) field.get(runtime)).stream()
                .map(KnowledgeBaseDTO::getId)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private KnowledgeTools factoryKnowledgeTool(JChatMind runtime) throws Exception {
        Field field = JChatMind.class.getDeclaredField("availableTools");
        field.setAccessible(true);
        for (ToolCallback callback : (List<ToolCallback>) field.get(runtime)) {
            if (!"KnowledgeTool".equals(callback.getToolDefinition().name())) {
                continue;
            }
            return findKnowledgeTool(callback, new IdentityHashMap<>(), 0);
        }
        throw new AssertionError("Agent runtime did not include KnowledgeTool");
    }

    private KnowledgeTools findKnowledgeTool(Object value, Map<Object, Boolean> visited, int depth) throws Exception {
        if (value instanceof KnowledgeTools knowledgeTools) {
            return knowledgeTools;
        }
        if (value == null || depth > 6 || visited.containsKey(value)) {
            throw new AssertionError("Unable to resolve the Agent runtime KnowledgeTool");
        }
        visited.put(value, Boolean.TRUE);
        for (Field field : value.getClass().getDeclaredFields()) {
            if (field.getType().isPrimitive()
                    || (field.getType().getName().startsWith("java.") && field.getType() != Object.class)) {
                continue;
            }
            field.setAccessible(true);
            try {
                return findKnowledgeTool(field.get(value), visited, depth + 1);
            } catch (AssertionError ignored) {
                // Continue scanning wrapper fields until the MethodToolCallback target is found.
            }
        }
        throw new AssertionError("Unable to resolve the Agent runtime KnowledgeTool");
    }

    @SuppressWarnings("unchecked")
    private List<String> effectiveKbIds(KnowledgeTools tool, List<String> requestedKbIds) throws Exception {
        Method loadContext = KnowledgeTools.class.getDeclaredMethod("loadRetrievalContext");
        Method resolve = KnowledgeTools.class.getDeclaredMethod(
                "resolveEffectiveKbIds",
                List.class,
                Class.forName("com.kama.jchatmind.model.dto.RagRetrievalContext")
        );
        loadContext.setAccessible(true);
        resolve.setAccessible(true);
        return (List<String>) resolve.invoke(tool, requestedKbIds, loadContext.invoke(tool));
    }
}
