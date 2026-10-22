package com.kama.jchatmind.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.JwtUtil;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.auth.TokenInterceptor;
import com.kama.jchatmind.config.WebConfig;
import com.kama.jchatmind.controller.BuiltinSkillController;
import com.kama.jchatmind.converter.ChatSessionConverter;
import com.kama.jchatmind.event.listener.ChatSessionExecutionCoordinator;
import com.kama.jchatmind.exception.GlobalExceptionHandler;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.impl.ChatSessionFacadeServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "g3.skill.http.postgres.l2", matches = "true")
class BuiltinSkillPostgresHttpL2Test {

    private static final String JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval";
    private static final String DB_USER = "rag_eval";
    private static final String JWT_SECRET =
            "jchatmind-skill-postgres-l2-test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final String ENDPOINT =
            "/api/agents/%s/skills/technical-decision-comparison/execute";

    @Test
    void executesTheJwtSkillHttpFlowAgainstProductionMappersAndRollsBackItsPostgresRows() throws Exception {
        try (AnnotationConfigWebApplicationContext context = newWebContext()) {
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class)
            );
            String ownerAgentId = UUID.randomUUID().toString();
            String corruptAgentId = UUID.randomUUID().toString();
            String ownerSessionId = UUID.randomUUID().toString();
            String corruptSessionId = UUID.randomUUID().toString();
            String kb1 = UUID.randomUUID().toString();
            String kb2 = UUID.randomUUID().toString();
            String foreignKb = UUID.randomUUID().toString();
            String token = context.getBean(JwtUtil.class).generateToken(42L);
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
            SkillToolProbe probe = context.getBean(SkillToolProbe.class);

            transaction.executeWithoutResult(transactionStatus -> {
                assertDatabaseIdentity(jdbcTemplate);
                createTemporaryTables(jdbcTemplate);
                insertFixtures(jdbcTemplate, ownerAgentId, corruptAgentId, ownerSessionId,
                        corruptSessionId, kb1, kb2, foreignKb);

                try {
                    mockMvc.perform(post(ENDPOINT.formatted(ownerAgentId))
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody(ownerSessionId, List.of(kb2))))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.code").value(200))
                            .andExpect(jsonPath("$.data.valid").value(true))
                            .andExpect(jsonPath("$.data.output.evidence[0].chunkId").value("postgres-chunk"))
                            .andExpect(jsonPath("$.data.output.evidence[0].kbId").value(kb2));

                    assertThat(probe.userId.get()).isEqualTo("42");
                    assertThat(probe.sessionId.get()).isEqualTo(ownerSessionId);
                    assertThat(probe.knowledgeBaseIds.get()).containsExactly(kb2);

                    mockMvc.perform(post(ENDPOINT.formatted(ownerAgentId))
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody(ownerSessionId, List.of(foreignKb))))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.code").value(500));

                    assertThat(probe.knowledgeBaseIds.get()).containsExactly(kb2);

                    mockMvc.perform(post(ENDPOINT.formatted(corruptAgentId))
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody(corruptSessionId, List.of())))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.code").value(500))
                            .andExpect(jsonPath("$.message").value("无权访问知识库"));

                    assertThat(probe.knowledgeBaseIds.get()).containsExactly(kb2);
                } catch (Exception exception) {
                    throw new IllegalStateException("Skill PostgreSQL HTTP L2 请求失败", exception);
                }
                transactionStatus.setRollbackOnly();
            });

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM knowledge_base WHERE id IN (?::uuid, ?::uuid, ?::uuid)",
                    Integer.class, kb1, kb2, foreignKb
            )).isZero();
        }
    }

    private AnnotationConfigWebApplicationContext newWebContext() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "skill-postgres-http-l2",
                Map.of(
                        "spring.datasource.url", JDBC_URL,
                        "spring.datasource.username", DB_USER,
                        "spring.datasource.password", "",
                        "mybatis.mapper-locations", "classpath*:mapper/*.xml",
                        "mybatis.type-handlers-package", "com.kama.jchatmind.typehandler",
                        "jwt.secret", JWT_SECRET,
                        "jwt.expiration", "3600"
                )
        ));
        context.register(SkillPostgresHttpConfiguration.class);
        context.refresh();
        return context;
    }

    private void assertDatabaseIdentity(JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("jchatmind_rag_eval");
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class))
                .isEqualTo(DB_USER);
    }

    private void createTemporaryTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("CREATE TEMP TABLE agent ("
                + "id UUID PRIMARY KEY, user_id BIGINT NOT NULL, name TEXT, description TEXT, "
                + "system_prompt TEXT, model TEXT, allowed_tools JSONB, chat_options JSONB, "
                + "created_at TIMESTAMP, updated_at TIMESTAMP) ON COMMIT DROP");
        jdbcTemplate.execute("CREATE TEMP TABLE agent_knowledge_base ("
                + "agent_id UUID NOT NULL, kb_id UUID NOT NULL, bound_by_user_id BIGINT NOT NULL, "
                + "bound_at TIMESTAMP NOT NULL, PRIMARY KEY(agent_id, kb_id)) ON COMMIT DROP");
        jdbcTemplate.execute("CREATE TEMP TABLE chat_session ("
                + "id UUID PRIMARY KEY, user_id TEXT NOT NULL, agent_id UUID NOT NULL, title TEXT, "
                + "metadata JSONB, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL) ON COMMIT DROP");
    }

    private void insertFixtures(
            JdbcTemplate jdbcTemplate,
            String ownerAgentId,
            String corruptAgentId,
            String ownerSessionId,
            String corruptSessionId,
            String kb1,
            String kb2,
            String foreignKb
    ) {
        jdbcTemplate.update("INSERT INTO agent VALUES "
                        + "(?::uuid, 42, 'owner-agent', '', '', 'test-model', '[\"KnowledgeTool\"]'::jsonb, "
                        + "'{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), "
                        + "(?::uuid, 42, 'corrupt-binding-agent', '', '', 'test-model', "
                        + "'[\"KnowledgeTool\"]'::jsonb, '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                ownerAgentId, corruptAgentId);
        jdbcTemplate.update("INSERT INTO knowledge_base (id, name, description, metadata, owner_id) "
                        + "VALUES (?::uuid, 'owner-kb-1', '', '{}'::jsonb, 42), "
                        + "(?::uuid, 'owner-kb-2', '', '{}'::jsonb, 42), "
                        + "(?::uuid, 'foreign-owner-kb', '', '{}'::jsonb, 84)",
                kb1, kb2, foreignKb);
        jdbcTemplate.update("INSERT INTO agent_knowledge_base VALUES "
                        + "(?::uuid, ?::uuid, 42, CURRENT_TIMESTAMP), "
                        + "(?::uuid, ?::uuid, 42, CURRENT_TIMESTAMP), "
                        + "(?::uuid, ?::uuid, 42, CURRENT_TIMESTAMP)",
                ownerAgentId, kb1, ownerAgentId, kb2, corruptAgentId, foreignKb);
        jdbcTemplate.update("INSERT INTO chat_session VALUES "
                        + "(?::uuid, '42', ?::uuid, 'owner-session', '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), "
                        + "(?::uuid, '42', ?::uuid, 'corrupt-binding-session', '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                ownerSessionId, ownerAgentId, corruptSessionId, corruptAgentId);
    }

    private byte[] requestBody(String sessionId, List<String> kbIds) throws Exception {
        return new ObjectMapper().writeValueAsBytes(Map.of(
                "sessionId", sessionId,
                "input", Map.of("question", "比较方案", "kbIds", kbIds)
        ));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @MapperScan("com.kama.jchatmind.mapper")
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            MybatisAutoConfiguration.class,
            JacksonAutoConfiguration.class
    })
    @Import({
            RequestScopeData.class,
            JwtUtil.class,
            TokenInterceptor.class,
            WebConfig.class,
            BuiltinSkillController.class,
            GlobalExceptionHandler.class,
            BuiltinSkillRegistry.class,
            BuiltinSkillExecutor.class,
            BuiltinSkillExecutionService.class,
            AgentKnowledgeBaseBindingService.class,
            KnowledgeBaseAccessService.class,
            ChatSessionConverter.class,
            ChatSessionFacadeServiceImpl.class
    })
    static class SkillPostgresHttpConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ChatSessionExecutionCoordinator chatSessionExecutionCoordinator() {
            return org.mockito.Mockito.mock(ChatSessionExecutionCoordinator.class);
        }

        @Bean
        ApplicationEventPublisher applicationEventPublisher() {
            return event -> {
            };
        }

        @Bean
        SkillToolProbe skillToolProbe() {
            return new SkillToolProbe();
        }

        @Bean
        SkillKnowledgeToolExecutor skillKnowledgeToolExecutor(SkillToolProbe probe) {
            return (userId, sessionId, question, knowledgeBaseIds) -> {
                probe.userId.set(userId);
                probe.sessionId.set(sessionId);
                probe.knowledgeBaseIds.set(List.copyOf(knowledgeBaseIds));
                RagRetrievalResult result = new RagRetrievalResult();
                result.setChunkId("postgres-chunk");
                result.setKbId(knowledgeBaseIds.get(0));
                return SkillKnowledgeToolResult.executed(List.of(result));
            };
        }
    }

    static class SkillToolProbe {

        private final AtomicReference<String> userId = new AtomicReference<>();
        private final AtomicReference<String> sessionId = new AtomicReference<>();
        private final AtomicReference<List<String>> knowledgeBaseIds = new AtomicReference<>();
    }
}
