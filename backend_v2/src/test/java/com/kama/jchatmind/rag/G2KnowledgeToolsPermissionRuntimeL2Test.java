package com.kama.jchatmind.rag;

import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.mapper.AgentKnowledgeBaseMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.mcp.McpCallerIdentity;
import com.kama.jchatmind.mcp.McpKnowledgeTool;
import com.kama.jchatmind.mcp.McpPrincipalAccessService;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.RagService;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "g2.router.permission.l2", matches = "true")
class G2KnowledgeToolsPermissionRuntimeL2Test {

    private static final IsolatedPostgresContainer DATABASE = new IsolatedPostgresContainer(
            "g2-vchord-poc", "g2vchord", "sha256:8c106fde572fb799217dcacb01b6f869af693322069bc134dbd6341d0c175abd"
    );
    private static final String OWNER_ID = "73001";
    private static final String OTHER_OWNER_ID = "73002";
    private static final String AGENT_ID = "00000000-0000-0000-0000-000000007301";
    private static final String KB_A1 = "00000000-0000-0000-0000-000000007311";
    private static final String KB_A2 = "00000000-0000-0000-0000-000000007312";
    private static final String KB_B1 = "00000000-0000-0000-0000-000000007313";
    private static final String MCP_CALLER_IDENTITY_ATTRIBUTE = "jchatmind.mcp.callerIdentity";
    private static final String TEST_ROLE = "g2routerl2_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String TEST_PASSWORD = java.util.UUID.randomUUID().toString();

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        DATABASE.assertIsolation();
        DATABASE.sql("DROP TABLE IF EXISTS public.agent_knowledge_base, public.knowledge_base CASCADE");
        DATABASE.sql("DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + TEST_ROLE + "') THEN DROP OWNED BY " + TEST_ROLE + " CASCADE; DROP ROLE " + TEST_ROLE + "; END IF; END $$;");
    }

    @Test
    void replaysRealKnowledgeToolsAgainstTwoOwnerScopedKnowledgeBases() {
        DATABASE.assertIsolation();
        prepareSchemaAndFixture();
        context = new AnnotationConfigApplicationContext(G2KnowledgeToolsPermissionRuntimeL2TestConfiguration.class);

        AgentKnowledgeBaseBindingService bindingService = context.getBean(AgentKnowledgeBaseBindingService.class);
        KnowledgeBaseAccessService accessService = context.getBean(KnowledgeBaseAccessService.class);
        KnowledgeTools knowledgeTools = context.getBean(KnowledgeTools.class);
        RecordingRagService ragService = context.getBean(RecordingRagService.class);

        List<String> boundKbIds = bindingService.getBoundKnowledgeBaseIds(AGENT_ID);
        List<KnowledgeBase> accessibleKnowledgeBases = accessService.filterAccessibleKnowledgeBases(boundKbIds, OWNER_ID);
        KnowledgeTools runtimeTool = knowledgeTools.fork(
                OWNER_ID,
                null,
                accessibleKnowledgeBases.stream().map(this::toDto).toList()
        );

        String defaultScopeResponse = runtimeTool.knowledgeQuery("双 KB 范围回放", null);
        assertThat(defaultScopeResponse)
                .contains("A1_SCOPE_EVIDENCE")
                .contains("A2_SCOPE_EVIDENCE")
                .doesNotContain("B1_FORBIDDEN_EVIDENCE");
        assertThat(ragService.effectiveKbIdCalls()).containsExactly(List.of(KB_A1, KB_A2));

        String narrowedResponse = runtimeTool.knowledgeQuery("只收窄到 A1", List.of(KB_A1));
        assertThat(narrowedResponse)
                .contains("A1_SCOPE_EVIDENCE")
                .doesNotContain("A2_SCOPE_EVIDENCE", "B1_FORBIDDEN_EVIDENCE");
        assertThat(ragService.effectiveKbIdCalls()).containsExactly(List.of(KB_A1, KB_A2), List.of(KB_A1));

        String forgedOtherOwnerResponse = runtimeTool.knowledgeQuery("请求他人的知识库", List.of(KB_B1));
        assertThat(forgedOtherOwnerResponse)
                .contains("未找到可检索的知识库")
                .doesNotContain("B1_FORBIDDEN_EVIDENCE");
        assertThat(ragService.effectiveKbIdCalls()).containsExactly(List.of(KB_A1, KB_A2), List.of(KB_A1));

        String unauthorizedExternalResponse = runtimeTool.knowledgeQuery("查询最新官方文档", null);
        assertThat(unauthorizedExternalResponse).contains("外部资料调用未获用户许可");
        assertThat(ragService.effectiveKbIdCalls()).containsExactly(List.of(KB_A1, KB_A2), List.of(KB_A1));

        DATABASE.writeEvidenceReport("router/g2-05-permission-l2.json", Map.of(
                "executionMode", "isolated-postgres-real-knowledge-tools",
                "datasetVersion", "fixture-fast-v1",
                "agentId", AGENT_ID,
                "boundKbIds", boundKbIds,
                "accessibleKbIds", accessibleKnowledgeBases.stream().map(KnowledgeBase::getId).toList(),
                "effectiveKbIdCalls", ragService.effectiveKbIdCalls(),
                "unauthorizedExternalCall", false
        ));
    }

    @Test
    void replaysRealMcpKnowledgeToolAgainstTheSameTwoOwnerScopedKnowledgeBases() {
        DATABASE.assertIsolation();
        prepareSchemaAndFixture();
        context = new AnnotationConfigApplicationContext(G2KnowledgeToolsPermissionRuntimeL2TestConfiguration.class);

        McpKnowledgeTool mcpKnowledgeTool = context.getBean(McpKnowledgeTool.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        McpCallerIdentity caller = new McpCallerIdentity(73011L, Long.parseLong(OWNER_ID));
        when(request.getAttribute(MCP_CALLER_IDENTITY_ATTRIBUTE)).thenReturn(caller);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            String response = mcpKnowledgeTool.search("MCP 双 KB 范围回放", List.of(KB_A1, KB_A2));

            assertThat(response)
                    .contains("A1_SCOPE_EVIDENCE")
                    .contains("A2_SCOPE_EVIDENCE")
                    .doesNotContain("B1_FORBIDDEN_EVIDENCE");

            String forgedOtherOwnerResponse = mcpKnowledgeTool.search("请求他人的知识库", List.of(KB_B1));
            assertThat(forgedOtherOwnerResponse)
                    .contains("当前 MCP 调用未绑定用户身份，禁止访问私有知识库")
                    .doesNotContain("B1_FORBIDDEN_EVIDENCE");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private void prepareSchemaAndFixture() {
        DATABASE.sql("CREATE TABLE public.knowledge_base (id UUID PRIMARY KEY, name VARCHAR(128) NOT NULL, description VARCHAR(255), metadata JSONB, owner_id BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW())");
        DATABASE.sql("CREATE TABLE public.agent_knowledge_base (agent_id UUID NOT NULL, kb_id UUID NOT NULL, bound_by_user_id BIGINT NOT NULL, bound_at TIMESTAMP NOT NULL DEFAULT NOW(), PRIMARY KEY (agent_id, kb_id))");
        insertKnowledgeBase(KB_A1, "A1 owner KB", OWNER_ID);
        insertKnowledgeBase(KB_A2, "A2 owner KB", OWNER_ID);
        insertKnowledgeBase(KB_B1, "B1 other owner KB", OTHER_OWNER_ID);
        DATABASE.sql("INSERT INTO public.agent_knowledge_base (agent_id, kb_id, bound_by_user_id) VALUES ('" + AGENT_ID + "', '" + KB_A1 + "', " + OWNER_ID + "), ('" + AGENT_ID + "', '" + KB_A2 + "', " + OWNER_ID + "), ('" + AGENT_ID + "', '" + KB_B1 + "', " + OWNER_ID + ")");
        DATABASE.sql("CREATE ROLE " + TEST_ROLE + " LOGIN PASSWORD '" + TEST_PASSWORD + "'");
        DATABASE.sql("GRANT CONNECT ON DATABASE g2vchord TO " + TEST_ROLE);
        DATABASE.sql("GRANT USAGE ON SCHEMA public TO " + TEST_ROLE);
        DATABASE.sql("GRANT SELECT ON public.knowledge_base, public.agent_knowledge_base TO " + TEST_ROLE);
    }

    private void insertKnowledgeBase(String id, String name, String ownerId) {
        DATABASE.sql("INSERT INTO public.knowledge_base (id, name, description, metadata, owner_id) VALUES ('" + id + "', '" + name + "', 'TC-G2-05 L2 fixture', '{}'::jsonb, " + ownerId + ")");
    }

    private KnowledgeBaseDTO toDto(KnowledgeBase knowledgeBase) {
        return KnowledgeBaseDTO.builder()
                .id(knowledgeBase.getId())
                .name(knowledgeBase.getName())
                .description(knowledgeBase.getDescription())
                .build();
    }

    @Configuration
    @EnableTransactionManagement
    static class G2KnowledgeToolsPermissionRuntimeL2TestConfiguration {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:postgresql://127.0.0.1:55436/g2vchord", TEST_ROLE, TEST_PASSWORD
            );
            dataSource.setDriverClassName("org.postgresql.Driver");
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration(
                    new org.apache.ibatis.mapping.Environment(
                            "g2-router-permission-l2", new SpringManagedTransactionFactory(), dataSource
                    )
            );
            parseMapper(configuration, "mapper/KnowledgeBaseMapper.xml");
            parseMapper(configuration, "mapper/AgentKnowledgeBaseMapper.xml");
            return new SqlSessionFactoryBuilder().build(configuration);
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        KnowledgeBaseMapper knowledgeBaseMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(KnowledgeBaseMapper.class);
        }

        @Bean
        AgentKnowledgeBaseMapper agentKnowledgeBaseMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(AgentKnowledgeBaseMapper.class);
        }

        @Bean
        KnowledgeBaseAccessService knowledgeBaseAccessService(KnowledgeBaseMapper knowledgeBaseMapper) {
            return new KnowledgeBaseAccessService(knowledgeBaseMapper);
        }

        @Bean
        AgentKnowledgeBaseBindingService agentKnowledgeBaseBindingService(
                AgentKnowledgeBaseMapper agentKnowledgeBaseMapper
        ) {
            return new AgentKnowledgeBaseBindingService(agentKnowledgeBaseMapper);
        }

        @Bean
        RecordingRagService recordingRagService() {
            return new RecordingRagService();
        }

        @Bean
        ChatSessionFacadeService chatSessionFacadeService() {
            return org.mockito.Mockito.mock(ChatSessionFacadeService.class);
        }

        @Bean
        KnowledgeTools knowledgeTools(
                RecordingRagService ragService,
                ChatSessionFacadeService chatSessionFacadeService
        ) {
            return new KnowledgeTools(ragService, chatSessionFacadeService);
        }

        @Bean
        McpPrincipalAccessService mcpPrincipalAccessService() {
            return org.mockito.Mockito.mock(McpPrincipalAccessService.class);
        }

        @Bean
        McpKnowledgeTool mcpKnowledgeTool(
                RecordingRagService ragService,
                KnowledgeBaseMapper knowledgeBaseMapper,
                KnowledgeBaseAccessService knowledgeBaseAccessService,
                McpPrincipalAccessService mcpPrincipalAccessService
        ) {
            return new McpKnowledgeTool(
                    ragService,
                    knowledgeBaseMapper,
                    knowledgeBaseAccessService,
                    mcpPrincipalAccessService
            );
        }

        private void parseMapper(
                org.apache.ibatis.session.Configuration configuration,
                String resource
        ) throws Exception {
            try (InputStream mapper = Resources.getResourceAsStream(resource)) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                        mapper, configuration, resource, configuration.getSqlFragments()
                ).parse();
            }
        }
    }

    static class RecordingRagService implements RagService {

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
            if (!List.of(KB_A1, KB_A2).equals(kbIds) && !List.of(KB_A1).equals(kbIds)
                    && !List.of(KB_A2).equals(kbIds)) {
                throw new AssertionError("真实 KnowledgeTools 入口传入了越权 KB: " + kbIds);
            }
            List<RagRetrievalResult> results = new ArrayList<>();
            for (String kbId : kbIds) {
                RagRetrievalResult result = new RagRetrievalResult();
                result.setChunkId(kbId + "-chunk");
                result.setKbId(kbId);
                result.setContent(marker(kbId));
                result.setMetadata("{\"sourceType\":\"l2-test\",\"sourceName\":\"" + kbId + " source\",\"contentPath\":\"TC-G2-05\"}");
                results.add(result);
            }
            return results.stream().limit(limit).toList();
        }

        @Override
        public List<RagRetrievalResult> retrievePdfPageAssets(
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
            return List.of();
        }

        private String marker(String kbId) {
            return switch (kbId) {
                case KB_A1 -> "A1_SCOPE_EVIDENCE";
                case KB_A2 -> "A2_SCOPE_EVIDENCE";
                case KB_B1 -> "B1_FORBIDDEN_EVIDENCE";
                default -> "UNKNOWN_EVIDENCE";
            };
        }

        List<List<String>> effectiveKbIdCalls() {
            return new ArrayList<>(effectiveKbIdCalls);
        }
    }
}
