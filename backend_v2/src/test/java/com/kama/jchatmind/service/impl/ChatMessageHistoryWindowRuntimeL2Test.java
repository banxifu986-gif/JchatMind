package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.controller.ChatMessageController;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.response.GetChatSessionResponse;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatMessageHistoryWindowRuntimeL2Test {

    private static final String DATABASE = "g5_chat_history_l2";
    private static final String DATABASE_USER = "g5_history";
    private static final int HISTORY_SIZE = 70;

    @Test
    void shouldReturnMoreThanRedisReplayWindowThroughProductionControllerMapperAndService() throws Exception {
        assumeTrue(Boolean.getBoolean("g5.chat.history.postgres.l2"));
        int port = Integer.getInteger("g5.chat.history.postgres.port", 0);
        assumeTrue(port >= 49152 && port <= 65535);

        String schema = "g5_history_" + UUID.randomUUID().toString().replace("-", "");
        String sessionId = UUID.randomUUID().toString();
        PGSimpleDataSource setupDataSource = dataSource(port, null);

        try (Connection connection = setupDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet identity = statement.executeQuery("SELECT current_database(), current_user")) {
                assertThat(identity.next()).isTrue();
                assertThat(identity.getString(1)).isEqualTo(DATABASE);
                assertThat(identity.getString(2)).isEqualTo(DATABASE_USER);
            }

            statement.execute("CREATE SCHEMA " + schema);
            try {
                insertHistory(connection, schema, sessionId);
                if (Boolean.getBoolean("g5.chat.history.redis.l2")) {
                    int redisPort = Integer.getInteger("g5.chat.history.redis.port", 0);
                    if (redisPort < 49152 || redisPort > 65535) {
                        throw new IllegalArgumentException("An isolated high Redis port is required for combined L2");
                    }
                    assertRedisReplayGap(redisPort, sessionId);
                }
                assertHistoryEndpoint(port, schema, sessionId);
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void insertHistory(Connection connection, String schema, String sessionId) throws Exception {
        String sql = "INSERT INTO " + schema + ".chat_message "
                + "(id, session_id, role, content, metadata, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + schema + ".chat_message ("
                    + "id UUID PRIMARY KEY, session_id UUID NOT NULL, role VARCHAR(32) NOT NULL, "
                    + "content TEXT NOT NULL, metadata JSONB, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL"
                    + ")");
        }
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            LocalDateTime firstCreatedAt = LocalDateTime.of(2026, 10, 13, 0, 0);
            for (int index = 0; index < HISTORY_SIZE; index++) {
                Timestamp createdAt = Timestamp.valueOf(firstCreatedAt.plusSeconds(index));
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, UUID.fromString(sessionId));
                insert.setString(3, index % 2 == 0 ? "user" : "assistant");
                insert.setString(4, "history-" + index);
                insert.setNull(5, Types.OTHER);
                insert.setTimestamp(6, createdAt);
                insert.setTimestamp(7, createdAt);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void assertHistoryEndpoint(int port, String schema, String sessionId) throws Exception {
        DataSource dataSource = dataSource(port, schema);
        Configuration configuration = new Configuration(
                new Environment("g5-chat-history", new JdbcTransactionFactory(), dataSource)
        );
        ClassPathResource mapperResource = new ClassPathResource("mapper/ChatMessageMapper.xml");
        try (InputStream mapperXml = mapperResource.getInputStream()) {
            new XMLMapperBuilder(
                    mapperXml,
                    configuration,
                    mapperResource.toString(),
                    configuration.getSqlFragments()
            ).parse();
        }
        SqlSessionFactory sqlSessionFactory = new org.apache.ibatis.session.SqlSessionFactoryBuilder()
                .build(configuration);
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);

        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            ChatMessageFacadeServiceImpl service = new ChatMessageFacadeServiceImpl(
                    sqlSession.getMapper(ChatMessageMapper.class),
                    new ChatMessageConverter(new ObjectMapper()),
                    sessionService(sessionId),
                    (ApplicationEventPublisher) event -> { },
                    requestScopeData
            );
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ChatMessageController(service)).build();
            mockMvc.perform(get("/api/chat-messages/session/{sessionId}", sessionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.chatMessages", hasSize(HISTORY_SIZE)))
                    .andExpect(jsonPath("$.data.chatMessages[0].content").value("history-0"))
                    .andExpect(jsonPath("$.data.chatMessages[69].content").value("history-69"));
        }
    }

    private static void assertRedisReplayGap(int port, String sessionId) throws Exception {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port)
        );
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        ObjectMapper objectMapper = new ObjectMapper();
        RedisChatSseEventStore eventStore = new RedisChatSseEventStore(redisTemplate, objectMapper);

        try {
            SseServiceImpl sender = new SseServiceImpl(objectMapper, eventStore);
            for (int index = 0; index < HISTORY_SIZE; index++) {
                sender.send(sessionId, SseMessage.builder()
                        .type(SseMessage.Type.AI_THINKING)
                        .build());
            }

            SseEmitter emitter = mock(SseEmitter.class);
            SseServiceImpl replayService = new SseServiceImpl(objectMapper, eventStore);
            assertThat(replayService.replayToEmitter(sessionId, emitter, 0)).isTrue();

            ArgumentCaptor<SseEmitter.SseEventBuilder> sentEvent =
                    ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
            verify(emitter).send(sentEvent.capture());
            String eventText = sentEvent.getValue().build().stream()
                    .map(part -> part.getData().toString())
                    .collect(Collectors.joining());
            assertThat(eventText).contains("id:70\n", "event:replay-gap\n");
        } finally {
            eventStore.delete(sessionId);
            connectionFactory.destroy();
        }
    }

    private static PGSimpleDataSource dataSource(int port, String schema) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl("jdbc:postgresql://127.0.0.1:" + port + "/" + DATABASE
                + (schema == null ? "" : "?currentSchema=" + schema));
        dataSource.setUser(DATABASE_USER);
        return dataSource;
    }

    private static ChatSessionFacadeService sessionService(String sessionId) {
        return (ChatSessionFacadeService) Proxy.newProxyInstance(
                ChatSessionFacadeService.class.getClassLoader(),
                new Class<?>[]{ChatSessionFacadeService.class},
                (proxy, method, arguments) -> method.getName().equals("getChatSession")
                        ? GetChatSessionResponse.builder()
                                .chatSession(ChatSessionVO.builder().id(sessionId).userId("7").build())
                                .build()
                        : null
        );
    }
}
