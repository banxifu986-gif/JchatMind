package com.kama.jchatmind.memory;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.mapper.UserMemoryCandidateMapper;
import com.kama.jchatmind.mapper.UserMemoryMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.MemoryExtractionResult;
import com.kama.jchatmind.service.impl.UserMemoryFacadeServiceImpl;
import com.kama.jchatmind.typehandler.PgVectorTypeHandler;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "g3.longmemeval.persistence.l2.enabled", matches = "true")
class LongMemEvalCandidatePersistenceRuntimeL2Test {

    private static final String ISOLATED_JDBC_URL =
            "jdbc:postgresql://127.0.0.1:55433/longmemeval_eval";
    private static final String ISOLATED_DATABASE = "longmemeval_eval";
    private static final long VIRTUAL_USER_ID = 980000000001L;

    @Test
    void persistsAndReadsPendingCandidateOnlyInsideRollbackTransaction() throws Exception {
        DataSource dataSource = dataSource();
        assertIsolatedDatabase(dataSource);

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTransactionFactory(new JdbcTransactionFactory());
        factoryBean.setMapperLocations(new ClassPathResource("mapper/UserMemoryCandidateMapper.xml"));
        SqlSessionFactory sessionFactory = factoryBean.getObject();
        assertThat(sessionFactory).isNotNull();

        try (SqlSession session = sessionFactory.openSession(false)) {
            try {
                Connection connection = session.getConnection();
                assertThat(connection.getMetaData().getURL()).isEqualTo(ISOLATED_JDBC_URL);
                assertThat(connection.getCatalog()).isEqualTo(ISOLATED_DATABASE);
                assertThat(connection.getAutoCommit()).isFalse();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO public.jchatmind_user (user_id)
                        VALUES (?)
                        ON CONFLICT (user_id) DO NOTHING
                        """)) {
                    statement.setLong(1, VIRTUAL_USER_ID);
                    statement.executeUpdate();
                }

                UserMemoryCandidateMapper mapper = session.getMapper(UserMemoryCandidateMapper.class);
                LocalDateTime now = LocalDateTime.now();
                UserMemoryCandidate candidate = UserMemoryCandidate.builder()
                        .userId(Long.toString(VIRTUAL_USER_ID))
                        .sessionId(UUID.randomUUID().toString())
                        .memoryType("PREFERENCE")
                        .content("prefers concise explanations")
                        .evidence("The user said they prefer concise explanations.")
                        .importance("medium")
                        .evidenceMessageId(UUID.randomUUID().toString())
                        .status(UserMemoryCandidate.STATUS_PENDING)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();

                assertThat(mapper.insert(candidate)).isEqualTo(1);
                assertThat(candidate.getId()).isNotBlank();
                UserMemoryCandidate persisted = mapper.selectByIdAndUserId(
                        candidate.getId(), Long.toString(VIRTUAL_USER_ID)
                );
                assertThat(persisted).isNotNull();
                assertThat(persisted.getStatus()).isEqualTo(UserMemoryCandidate.STATUS_PENDING);
                assertThat(persisted.getEvidenceMessageId()).isEqualTo(candidate.getEvidenceMessageId());
                assertThat(mapper.markDiscardedIfPending(
                        candidate.getId(), Long.toString(VIRTUAL_USER_ID)
                )).isEqualTo(1);
                assertThat(mapper.selectByIdAndUserId(
                        candidate.getId(), Long.toString(VIRTUAL_USER_ID)
                ).getStatus()).isEqualTo(UserMemoryCandidate.STATUS_DISCARDED);
            } finally {
                session.rollback();
            }
        }
    }

    @Test
    void productionExtractionServicePersistsAnEvidenceLinkedPendingCandidate() throws Exception {
        DataSource dataSource = dataSource();
        assertIsolatedDatabase(dataSource);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTransactionFactory(new JdbcTransactionFactory());
        factoryBean.setTypeHandlers(new PgVectorTypeHandler());
        factoryBean.setMapperLocations(
                new ClassPathResource("mapper/UserMemoryMapper.xml"),
                new ClassPathResource("mapper/UserMemoryCandidateMapper.xml")
        );
        SqlSessionFactory sessionFactory = factoryBean.getObject();
        assertThat(sessionFactory).isNotNull();

        try (SqlSession session = sessionFactory.openSession(false)) {
            try {
                Connection connection = session.getConnection();
                insertVirtualUser(connection);
                UserMemoryMapper memoryMapper = session.getMapper(UserMemoryMapper.class);
                UserMemoryCandidateMapper candidateMapper = session.getMapper(UserMemoryCandidateMapper.class);
                ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
                String userId = Long.toString(VIRTUAL_USER_ID);
                String sessionId = UUID.randomUUID().toString();
                List<ChatMessageDTO> messages = userMessages(sessionId);
                when(chatMessageFacadeService.countUserMessagesBySessionId(sessionId, userId)).thenReturn(3);
                when(chatMessageFacadeService.getChatMessagesBySessionIdRecently(sessionId, 8, userId))
                        .thenReturn(messages);
                RequestScopeData requestScopeData = new RequestScopeData();
                requestScopeData.setUserId(VIRTUAL_USER_ID);
                requestScopeData.setLogin(true);
                @SuppressWarnings("unchecked")
                ObjectProvider<ChatClientRegistry> registryProvider = mock(ObjectProvider.class);
                registry(registryProvider, extractionResponse());
                UserMemoryFacadeServiceImpl memoryService = new UserMemoryFacadeServiceImpl(
                        memoryMapper,
                        candidateMapper,
                        chatMessageFacadeService,
                        requestScopeData,
                        registryProvider,
                        null
                );

                assertThat(memoryService.extractMemoryCandidates(userId, sessionId))
                        .isEqualTo(MemoryExtractionResult.EXTRACTED);
                List<UserMemoryCandidate> candidates = candidateMapper.selectByUserId(userId);
                assertThat(candidates).hasSize(1);
                assertThat(candidates.get(0).getStatus()).isEqualTo(UserMemoryCandidate.STATUS_PENDING);
                assertThat(candidates.get(0).getEvidenceMessageId()).isEqualTo(messages.get(1).getId());
                assertThat(candidates.get(0).getEvidence()).isEqualTo(messages.get(1).getContent());
                assertThat(memoryMapper.selectByUserId(userId)).isEmpty();
                verify(chatMessageFacadeService).getChatMessagesBySessionIdRecently(sessionId, 8, userId);
            } finally {
                session.rollback();
            }
        }
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(ISOLATED_JDBC_URL);
        dataSource.setUsername("longmemeval_eval");
        dataSource.setPassword("");
        return dataSource;
    }

    private void insertVirtualUser(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.jchatmind_user (user_id)
                VALUES (?)
                ON CONFLICT (user_id) DO NOTHING
                """)) {
            statement.setLong(1, VIRTUAL_USER_ID);
            statement.executeUpdate();
        }
    }

    private List<ChatMessageDTO> userMessages(String sessionId) {
        return List.of(
                userMessage(sessionId, "我喜欢清晰的回答"),
                userMessage(sessionId, "请优先给出简洁说明"),
                userMessage(sessionId, "我通常使用 Java")
        );
    }

    private ChatMessageDTO userMessage(String sessionId, String content) {
        return ChatMessageDTO.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .role(ChatMessageDTO.RoleType.USER)
                .content(content)
                .build();
    }

    private ChatClient extractionClient(String response) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec result = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(result);
        when(result.content()).thenReturn(response);
        return client;
    }

    private void registry(
            ObjectProvider<ChatClientRegistry> registryProvider,
            String response
    ) {
        ChatClientRegistry chatClientRegistry = new ChatClientRegistry(
                Map.of("deepseek-chat", extractionClient(response))
        );
        when(registryProvider.getIfAvailable()).thenReturn(chatClientRegistry);
    }

    private String extractionResponse() {
        return """
                [
                  {
                    "type": "PREFERENCE",
                    "content": "prefers concise explanations",
                    "importance": "medium",
                    "should_persist": true,
                    "evidence_message_index": 1
                  }
                ]
                """;
    }

    private void assertIsolatedDatabase(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(ISOLATED_JDBC_URL);
            assertThat(connection.getCatalog()).isEqualTo(ISOLATED_DATABASE);
            assertThat(connection.getAutoCommit()).isTrue();
            try (PreparedStatement statement = connection.prepareStatement("SELECT current_database()")) {
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo(ISOLATED_DATABASE);
                }
            }
        }
    }
}
