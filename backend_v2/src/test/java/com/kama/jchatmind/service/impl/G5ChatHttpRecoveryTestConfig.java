package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.JwtUtil;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.auth.TokenInterceptor;
import com.kama.jchatmind.config.RedisConfig;
import com.kama.jchatmind.config.WebConfig;
import com.kama.jchatmind.controller.ChatMessageController;
import com.kama.jchatmind.controller.SseController;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.ChatSessionConverter;
import com.kama.jchatmind.event.listener.ChatSessionExecutionCoordinator;
import com.kama.jchatmind.exception.GlobalExceptionHandler;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;

@TestConfiguration
@EnableAutoConfiguration
@Import(RedisConfig.class)
public class G5ChatHttpRecoveryTestConfig {

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://127.0.0.1:" + requiredPort("g5.chat.http.pg.port")
                + "/g5_chat_http_l2");
        dataSource.setUsername("g5_http");
        return dataSource;
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                "127.0.0.1", requiredPort("g5.chat.http.redis.port")
        ));
    }

    @Bean
    public SqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(
                new ClassPathResource("mapper/ChatSessionMapper.xml"),
                new ClassPathResource("mapper/ChatMessageMapper.xml")
        );
        return factory;
    }

    @Bean
    public MapperFactoryBean<ChatSessionMapper> chatSessionMapper(SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<ChatSessionMapper> factory = new MapperFactoryBean<>(ChatSessionMapper.class);
        factory.setSqlSessionFactory(sqlSessionFactory);
        return factory;
    }

    @Bean
    public MapperFactoryBean<ChatMessageMapper> chatMessageMapper(SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<ChatMessageMapper> factory = new MapperFactoryBean<>(ChatMessageMapper.class);
        factory.setSqlSessionFactory(sqlSessionFactory);
        return factory;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
    public RequestScopeData requestScopeData() {
        return new RequestScopeData();
    }

    @Bean
    public ChatSessionExecutionCoordinator chatSessionExecutionCoordinator() {
        return new ChatSessionExecutionCoordinator();
    }

    @Bean
    public ChatSessionConverter chatSessionConverter(ObjectMapper objectMapper) {
        return new ChatSessionConverter(objectMapper);
    }

    @Bean
    public ChatMessageConverter chatMessageConverter(ObjectMapper objectMapper) {
        return new ChatMessageConverter(objectMapper);
    }

    @Bean
    public ChatSessionFacadeServiceImpl chatSessionFacadeService(
            ChatSessionMapper mapper,
            ChatSessionConverter converter,
            RequestScopeData requestScopeData,
            ChatSessionExecutionCoordinator coordinator,
            ApplicationEventPublisher publisher
    ) {
        return new ChatSessionFacadeServiceImpl(mapper, converter, requestScopeData, coordinator, publisher);
    }

    @Bean
    public ChatMessageFacadeServiceImpl chatMessageFacadeService(
            ChatMessageMapper mapper,
            ChatMessageConverter converter,
            ChatSessionFacadeServiceImpl sessionService,
            ApplicationEventPublisher publisher,
            RequestScopeData requestScopeData
    ) {
        return new ChatMessageFacadeServiceImpl(mapper, converter, sessionService, publisher, requestScopeData);
    }

    @Bean
    public RedisChatSseEventStore redisChatSseEventStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        return new RedisChatSseEventStore(redisTemplate, objectMapper);
    }

    @Bean
    public SseServiceImpl sseService(ObjectMapper objectMapper, RedisChatSseEventStore eventStore) {
        return new SseServiceImpl(objectMapper, eventStore);
    }

    @Bean
    public SseController sseController(
            RequestScopeData requestScopeData,
            ChatSessionFacadeServiceImpl sessionService,
            SseServiceImpl sseService
    ) {
        return new SseController(requestScopeData, sessionService, sseService);
    }

    @Bean
    public ChatMessageController chatMessageController(ChatMessageFacadeServiceImpl service) {
        return new ChatMessageController(service);
    }

    @Bean
    public JwtUtil jwtUtil() {
        return new JwtUtil();
    }

    @Bean
    public TokenInterceptor tokenInterceptor(RequestScopeData requestScopeData, JwtUtil jwtUtil) {
        return new TokenInterceptor(requestScopeData, jwtUtil);
    }

    @Bean
    public WebConfig webConfig(TokenInterceptor tokenInterceptor) {
        return new WebConfig(tokenInterceptor);
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    private int requiredPort(String property) {
        int port = Integer.getInteger(property, 0);
        if (port < 49152 || port > 65535) {
            throw new IllegalStateException("An isolated high port is required for " + property);
        }
        return port;
    }
}
